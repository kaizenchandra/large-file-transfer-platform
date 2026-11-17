package com.example.drive;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Storage boundary metrics use only fixed operation/outcome tags, never object identifiers.
 */
@Primary
@Component
public class MeteredObjectStore implements ObjectStore {
    private final S3Objects delegate;
    private final MeterRegistry metrics;

    public MeteredObjectStore(S3Objects delegate, MeterRegistry metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    private <T> T measured(String operation, Supplier<T> work) {
        var sample = Timer.start(metrics);
        String outcome = "success";
        try {
            return work.get();
        } catch (RuntimeException failure) {
            outcome = "failure";
            throw failure;
        } finally {
            sample.stop(metrics.timer("drive.storage", "operation", operation, "outcome", outcome));
        }
    }

    public void put(String key, byte[] bytes, String contentType) { delegate.put(key,bytes,contentType); }

    public String initiate(String key) {
        return measured("initiate", () -> delegate.initiate(key));
    }

    public Map<String, Object> part(String key, String upload, int number, String checksum) {
        return measured("sign_part", () -> delegate.part(key, upload, number, checksum));
    }

    public List<Map<String, Object>> parts(String key, String upload) {
        return measured("list_parts", () -> delegate.parts(key, upload));
    }

    public void complete(String key, String upload, long size, long partSize, Map<Integer, String> checksums) {
        measured("complete", () -> {
            delegate.complete(key, upload, size, partSize, checksums);
            return null;
        });
    }

    public void verify(String key, long size, String checksum) {
        measured("verify", () -> {
            delegate.verify(key, size, checksum);
            return null;
        });
        metrics.counter("drive.storage.verified.bytes").increment(size);
    }

    public java.io.InputStream open(String key) {
        return measured("open_scan", () -> delegate.open(key));
    }

    public void abort(String key, String upload) {
        measured("abort", () -> {
            delegate.abort(key, upload);
            return null;
        });
    }

    public void delete(String key) {
        measured("delete", () -> {
            delegate.delete(key);
            return null;
        });
    }

    public String download(String key, String name) {
        return measured("sign_download", () -> delegate.download(key, name));
    }
}
