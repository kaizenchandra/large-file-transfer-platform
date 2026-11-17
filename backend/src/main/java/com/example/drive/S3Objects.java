package com.example.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Component
public class S3Objects implements ObjectStore, AutoCloseable {
    private final S3Client s3;
    private final S3Presigner signer;
    private final String bucket;

    public S3Objects(@Value("${drive.s3.endpoint}") String endpoint, @Value("${drive.s3.public-endpoint}") String publicEndpoint, @Value("${drive.s3.region}") String region, @Value("${drive.s3.bucket}") String bucket) {
        this.bucket = bucket;
        var config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        s3 = S3Client.builder().httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder().socketTimeout(Duration.ofMinutes(3)).connectionTimeout(Duration.ofSeconds(3)).connectionAcquisitionTimeout(Duration.ofSeconds(3)).maxConnections(16)).region(Region.of(region)).endpointOverride(URI.create(endpoint)).serviceConfiguration(config)
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofMinutes(10)).apiCallAttemptTimeout(Duration.ofMinutes(5)).build()).build();
        signer = S3Presigner.builder().region(Region.of(region)).endpointOverride(URI.create(publicEndpoint)).serviceConfiguration(config).build();
    }

    @Override
    public void close() {
        signer.close();
        s3.close();
    }

    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).checksumSHA256(Rules.hash(bytes)),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    }

    public String initiate(String key) {
        return s3.createMultipartUpload(b -> b.bucket(bucket).key(key).checksumAlgorithm(ChecksumAlgorithm.SHA256)).uploadId();
    }

    public Map<String, Object> part(String key, String upload, int number, String checksum) {
        var p = signer.presignUploadPart(b -> b.signatureDuration(Duration.ofMinutes(10)).uploadPartRequest(r -> r.bucket(bucket).key(key).uploadId(upload).partNumber(number).checksumSHA256(checksum)));
        return Map.of("url", p.url().toString(), "headers", p.signedHeaders(), "expiresInSeconds", 600);
    }

    private List<Part> listed(String key, String upload) {
        var result = new ArrayList<Part>();
        Integer marker = null;
        do {
            var request = ListPartsRequest.builder().bucket(bucket).key(key).uploadId(upload).partNumberMarker(marker).build();
            var page = s3.listParts(request);
            result.addAll(page.parts());
            marker = Boolean.TRUE.equals(page.isTruncated()) ? page.nextPartNumberMarker() : null;
        } while (marker != null);
        return result;
    }

    public List<Map<String, Object>> parts(String key, String upload) {
        return listed(key, upload).stream().map(p -> Map.<String, Object>of("number", p.partNumber(), "size", p.size(), "checksum", Objects.toString(p.checksumSHA256(), ""))).toList();
    }

    public void complete(String key, String upload, long size, long partSize, Map<Integer, String> checksums) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return;
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
        }
        var parts = listed(key, upload);
        int count = Rules.partCount(size, partSize);
        Rules.require(parts.size() == count, 409, "Missing parts");
        var completed = new ArrayList<CompletedPart>();
        for (int i = 0; i < count; i++) {
            var p = parts.get(i);
            long expected = i == count - 1 ? size - partSize * i : partSize;
            Rules.require(p.partNumber() == i + 1 && p.size() == expected && Objects.equals(checksums.get(i + 1), p.checksumSHA256()), 409, "Part size or checksum mismatch");
            completed.add(CompletedPart.builder().partNumber(i + 1).eTag(p.eTag()).checksumSHA256(p.checksumSHA256()).build());
        }
        s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload).multipartUpload(m -> m.parts(completed)));
    }

    public void verify(String key, long size, String sha256) {
        // Stream verification avoids trusting user-supplied metadata or multipart ETags.
        try (var stream = open(key)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            int n;
            while ((n = stream.read(buffer)) != -1) {
                total += n;
                digest.update(buffer, 0, n);
            }
            Rules.require(total == size && Base64.getEncoder().encodeToString(digest.digest()).equals(sha256), 422, "Object integrity mismatch");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Verification failed", e);
        }
    }

    public java.io.InputStream open(String key) {
        var metadata=s3.headObject(b->b.bucket(bucket).key(key));
        final long size=metadata.contentLength();final String validator=metadata.eTag();
        Rules.require(validator!=null&&!validator.isBlank(),503,"Storage validator required");
        return new java.io.InputStream(){
            long position=0,end=-1;boolean closed=false;
            software.amazon.awssdk.core.ResponseInputStream<GetObjectResponse> current;
            private void next() throws java.io.IOException {
                if(closed)throw new java.io.IOException("Stream closed");
                if(current!=null || position>=size)return;
                end=Math.min(size,position+16L*1024*1024)-1;
                current=s3.getObject(b->b.bucket(bucket).key(key).range("bytes="+position+"-"+end).ifMatch(validator));
                if(current.response().sdkHttpResponse().statusCode()!=206 || !Objects.equals(current.response().contentRange(),"bytes "+position+"-"+end+"/"+size) || current.response().contentLength()!=end-position+1){current.abort();current=null;throw new java.io.IOException("Invalid storage range response");}
            }
            @Override public int read() throws java.io.IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
            @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException {
                java.util.Objects.checkFromIndexSize(offset,length,bytes.length);if(length==0)return 0;next();if(position>=size)return -1;
                int n=current.read(bytes,offset,(int)Math.min(length,end-position+1));
                if(n<0)throw new java.io.IOException("Truncated storage range");position+=n;
                if(position==end+1){current.close();current=null;}return n;
            }
            @Override public void close(){closed=true;if(current!=null){current.abort();current=null;}}
        };
    }

    public void abort(String key, String upload) {
        if (upload == null) return;
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
        }
    }

    public void delete(String key) {
        // A versioned bucket requires explicit version inventory, which this collector does not implement.
        var versioning = s3.getBucketVersioning(b -> b.bucket(bucket)).status();
        Rules.require(versioning == null, 503, "Object GC requires a never-versioned bucket");
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    public String download(String key, String name) {
        var disposition = "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return signer.presignGetObject(b -> b.signatureDuration(Duration.ofSeconds(60)).getObjectRequest(r -> r.bucket(bucket).key(key).responseContentDisposition(disposition))).url().toString();
    }
}
