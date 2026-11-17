FROM golang:1.26-alpine AS build
RUN apk add --no-cache git
RUN git clone --depth 1 --branch RELEASE.2025-09-07T16-13-09Z https://github.com/minio/minio.git /src
WORKDIR /src
RUN go build -mod=readonly -p 2 -trimpath -ldflags='-s -w' -o /minio .
FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl
COPY --from=build /minio /usr/local/bin/minio
COPY --from=build /src/LICENSE /licenses/minio-AGPL-LICENSE
RUN mkdir /data && chown 10001:10001 /data
USER 10001
ENTRYPOINT ["minio"]
