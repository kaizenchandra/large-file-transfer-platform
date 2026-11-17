
## Reproduce the scanner-review workload

Build with Java 21, then use the immutable launcher `python3 sandbox/run_api.py --server.port=28083`. Configure the separate `drivebenchscan` PostgreSQL database, MinIO localhost:39000 and REQUIRED ClamAV at localhost:23310; set LARGE_SCAN_REVIEW_MAX_BYTES=20000000000 and SCAN_POLICY=clamav-large-signature-review-v1. Keep the ordinary 100 MiB whole-file scanner policy unchanged. The benchmark CLI requires a dedicated state directory:

```sh
python3 benchmarks/large_transfer.py --size 20000000000 --directory /tmp/harbor-scan20-benchmark --api http://localhost:28083/api/v1 --provider minio-scanner-enabled-review --expect-review --report test-results/scanner-enabled-20gb.json
```

Use fresh state/output paths for new measured runs; the driver otherwise resumes existing sessions. This driver expects the explicit REVIEW_REQUIRED outcome, not a successful normal download. Default application configuration does not enable large review admission.
