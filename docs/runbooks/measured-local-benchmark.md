# Reproducing the local benchmark

Use dedicated local ports and disposable databases. Drivers create retained logical files. The fixture repeats a seeded 1 MiB pseudorandom block; it is compressible and is not representative video content. Exactly 20,000,000,000 bytes is decimal 20 GB (18.626 GiB). Tests ran on a shared workstation with other services active; throughput is not a production capacity claim.

## Start infrastructure

From the project directory:

```sh
docker compose -p harbor-benchmark -f compose.benchmark.yml up -d
docker build -t harbor-minio:2025-09-07 infrastructure/minio
docker compose -p harbor-minio -f compose.minio-benchmark.yml up -d
docker build -t harbor-media:local sandbox
```

Initialize the private `drive` bucket in MinIO and a separate PostgreSQL database `drivebenchminio`. MinIO's loopback endpoint is localhost:39000; its reference credentials in the Compose file are development-only. PostgreSQL is localhost:25432. Keep the scanner-enabled `drive` database separate from the explicitly scan-bypassed large-file database. Image identities from the executed runs are recorded under `test-results/`.

## APIs

Build with Java 21. Run backend services through `python3 sandbox/run_api.py --server.port=PORT`, which copies the JAR to a checksum-named immutable runtime path. Never run from `backend/target` while Maven may repackage that JAR.

The scanner-enabled media API uses port 28080, DATABASE_URL ending in `/drive`, SCAN_MODE=REQUIRED, CLAMAV_PORT=23310, S3_ENDPOINT/S3_PUBLIC_ENDPOINT=http://localhost:29000, local fixture credentials `test`, and the authenticated loopback sandbox broker at localhost:19091. Set MEDIA_SANDBOX_URL and MEDIA_SANDBOX_TOKEN. The broker is started with SANDBOX_TOKEN using `python3 sandbox/broker.py`.

The large-file API uses port 28082, DATABASE_URL ending in `/drivebenchminio`, SCAN_MODE=DISABLED, S3_ENDPOINT/S3_PUBLIC_ENDPOINT=http://localhost:39000 and the reference MinIO credentials. DRIVE_LOCAL=true, LOCAL_PASSWORD=harbor-local-benchmark-password and DATABASE_PASSWORD=harbor-benchmark-database are test settings. The 512 MiB JVM heap is explicit in the launcher.

This 20 GB profile **bypasses malware scanning**. The REQUIRED scanner policy rejects 20 GB with HTTP 413 before transfer. Do not describe the benchmark as a fully security-cleared 20 GB media pipeline.

## Execute measured workloads

```sh
python3 benchmarks/large_transfer.py --size 20000000000 --api http://localhost:28082/api/v1 --directory /tmp/harbor-minio-benchmark --provider minio-source-RELEASE.2025-09-07T16-13-09Z --report test-results/transfer-20gb-minio.json
python3 benchmarks/media_e2e.py
SANDBOX_TOKEN=YOUR_LOCAL_TOKEN python3 sandbox/test_broker.py
```

The large driver bounds upload/download concurrency to three, hashes the source incrementally, polls durable completion and independently verifies ordered downloaded ranges against whole-file SHA-256. Its parallel download is an in-memory **bounded-range correctness driver**, not a 20 GB browser disk-assembly benchmark. Interrupted attempt files are retained separately; a resumed run must not claim throughput for bytes uploaded in an earlier attempt.

For browser recovery, start the Next.js development server with HARBOR_RECOVERY_LAB=1 and API_ORIGIN=http://localhost:28080 on port 3012. Then run `npm run test:restart` from frontend. Install Playwright Chromium if needed. The test closes and relaunches persistent Chromium after storage-confirmed upload parts, and again after a 24 MiB disk checkpoint. It persists a real OPFS file handle in IndexedDB. This tests browser process restart; it does not automate operating-system permission prompts for a user-selected external file. The harness route returns 404 unless explicitly enabled.

## Measured defects and scope

LocalStack 4.10.0 advertised a 20 GB object but returned a 206 response with Content-Range `bytes 0-15/20000000000`, Content-Length 16 and zero body bytes. Publication was withheld. The observed contract failure is recorded in `test-results/emulator-range-failure.json`; the MinIO measurements are separate. Server storage reads now use bounded 16 MiB ranges pinned by ETag, checking response status, exact interval and length before feeding an incremental whole-file digest.

Record raw JSON before drawing conclusions. Local timing, one transfer, synthetic data and five-second heap samples do not establish 100,000-transfer capacity, p99 SLOs, production scanning support, or global durability.

## Cleanup after inspection

Large fixtures and the private MinIO object were retained so the measured run can be inspected. Stop only Harbor-owned API/broker processes by their recorded command lines. `docker compose -p harbor-benchmark -f compose.benchmark.yml down` removes the disposable PostgreSQL/LocalStack/ClamAV environment. The directly started MinIO test container can be removed with `docker rm -f harbor-benchmark-minio` after inspection; that removes its 20 GB object. The generated source fixture is `/tmp/harbor-large-transfer/source.bin`, referenced by the symlink under `/tmp/harbor-minio-benchmark`. Removing it releases 20 GB, and the driver can regenerate it from seed 42. Keep `test-results/` as the measurement record. Do not prune Docker globally or remove unrelated project containers.
