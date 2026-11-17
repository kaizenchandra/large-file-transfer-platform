#!/bin/sh
awslocal s3api create-bucket --bucket drive
# Browser multipart uploads use signed PUT URLs on the public storage endpoint.
awslocal s3api put-bucket-cors --bucket drive --cors-configuration '{"CORSRules":[{"AllowedOrigins":["http://localhost:3012","http://localhost:3011","http://127.0.0.1:3011","http://localhost:18080","http://127.0.0.1:18080","http://localhost:8080","http://127.0.0.1:8080"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedHeaders":["*"],"ExposeHeaders":["ETag","Content-Range","Accept-Ranges","x-amz-checksum-sha256"],"MaxAgeSeconds":3600}]}'
