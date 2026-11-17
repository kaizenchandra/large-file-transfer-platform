# Measured local results — 2026-11-17

## 20 GB transfer: MinIO, one client

Exactly 20,000,000,000 bytes; 2,385 multipart uploads; three parallel upload requests and three parallel download ranges. The workload used a repeated seeded 1 MiB pseudorandom block. It is compressible. MinIO was source-built from RELEASE.2025-09-07T16-13-09Z. Tests ran on a shared workstation with concurrent services and regression tests; these are not isolated production capacity measurements.

| Measurement | Observed |
|---|---:|
| Source hashing | 10.98 s |
| Multipart PUT phase | 124.74 s |
| Upload throughput | 152.90 MiB/s |
| Verification and logical publication | 61.93 s |
| Parallel ranged download and independent hash | 169.50 s |
| Download throughput | 112.53 MiB/s |
| Total driver time, existing source fixture | 367.64 s |
| Part retries | 0 |
| Client peak RSS (macOS resource measurement) | 54.64 MiB |
| Maximum observed JVM heap, five-second samples | 80.27 MiB |
| JVM configured heap ceiling | 512 MiB |

Source and downloaded SHA-256 (base64): `oca3kgCmD9udlVcnohSnG2PgVwaD8QOXNqZZOtEGJFY=`. All bytes matched. The durable inspection job also reached SUCCEEDED with one attempt. The Python download driver uses bounded in-memory ranges; **this is not a 20 GB browser disk-write benchmark**.

**Malware scanning was explicitly DISABLED in this local transfer profile.** The REQUIRED scanner profile separately returned HTTP 413 for 20 GB, in 0.443 seconds, under its 100 MiB admission policy. These results do not establish a security-cleared 20 GB media pipeline.

## Scanner-enabled media end to end

Fresh logical uploads, asynchronous completion and ClamAV scanning, network-disabled sandbox parser, fenced artifact registration, authorized preview download and SHA-256 validation:

| Fixture | Full-flow duration | Preview bytes |
|---|---:|---:|
| pdf | 5.70 s | 12,928 |
| mp4 | 5.56 s | 2,146 |

PDF processing renders its first page. Video processing extracts its first frame, not a full transcode. Source admission is 64 MiB for these sandbox processors. Containers have one CPU, 512 MiB memory, 64 PIDs, no network/capabilities, a read-only root, non-root UID, 64 MiB scratch and 16 MiB output tmpfs, an 8 MiB output-file cap, and a 45-second parser deadline.

## Real Chromium restart recovery

A 41,943,040-byte fixture resumed after two provider-confirmed upload parts and a complete persistent-Chromium close/relaunch. Download resumed after a 25,165,824-byte committed checkpoint. A native OPFS FileSystemFileHandle was structured-cloned through IndexedDB across the restart. Final destination size and SHA-256 matched. These checks did not mock the filesystem. They did not automate external-file permission dialogs or process-kill crashes.

## Measured failures and corrections

- LocalStack 4.10.0 accepted all 20 GB parts, then advertised a valid object/range but returned zero bytes for a declared 16-byte range. The platform withheld publication. Interrupted/retried attempts are retained, not counted as successful throughput runs.
- Whole-object SDK reads timed out in that failure path. Storage reads now validate bounded 16 MiB responses against HEAD size and a pinned ETag while calculating whole-file SHA-256 incrementally.
- A local scanner-enabled API was started from a Maven target JAR and that JAR was repackaged during testing, causing lazy-class-loading failures. Services now have an immutable-JAR launcher. Admission was retested successfully after this correction.
- The browser recovery origin was missing from storage CORS; it is now included in local test configuration.
- Docker artifact extraction from tmpfs failed; the broker now extracts through a bounded command in the live container.

## Evidence and reproduction

[20 GB raw result](../test-results/transfer-20gb-minio.json), [job result](../test-results/processing-20gb-minio.json), [media results](../test-results/media-e2e.json), [browser restart](../test-results/browser-restart.json), [emulator failure](../test-results/emulator-range-failure.json), [scanner admission](../test-results/scanner-20gb-admission.json), [heap samples](../test-results/heap-samples-28082.json), [run instructions](runbooks/measured-local-benchmark.md).

No repeated-run variance, packet-loss profile, high-concurrency capacity, p99 SLO, production scanning throughput, CDN performance or cloud cost has been measured. Successful local transfer is not production-readiness evidence.
