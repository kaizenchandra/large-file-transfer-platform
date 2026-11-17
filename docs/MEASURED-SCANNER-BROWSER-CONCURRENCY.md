# Scanner-enabled ingestion, browser disk recovery and concurrency — measured 2026-11-17

## Scanner-enabled 20 GB ingestion

The test uploaded exactly 20,000,000,000 bytes with REQUIRED scanning, then performed independent server-side integrity verification and real ClamAV signature screening over 598 overlapping segments. All original bytes were covered. The outcome was **QUARANTINED / REVIEW_REQUIRED**, with no downloadable logical version; the normal download API returned HTTP 404. No 20 GB full-file CLEAN verdict is claimed.

| Measurement | Observed |
|---|---:|
| Multipart upload | 102.29 s |
| Verification, screening, and observed review transition | 547.37 s |
| Total driver time | 660.66 s |
| Unique source bytes covered | 20,000,000,000 |
| Scanner windows | 598 |
| Part retries | 0 |

Windows are 32 MiB with 64 KiB overlap. Engine response: `ClamAV 1.5.4/28177/Sun Nov  8 06:23:52 2026`. The signature database date is bundled in that response; a fresh-feed update/freshness gate was not validated. ClamAV documents a technical 2 GB per-file limit ([upstream configuration](https://github.com/Cisco-Talos/clamav/blob/main/etc/clamd.conf.sample)). Segmentation does not preserve arbitrary file structure, embedded formats or unbounded signature context, so no-finding screening cannot authorize publication. The ordinary admission policy still applies unless review mode is explicitly enabled. See [review policy](runbooks/large-scan-review.md).

## Real 20 GB browser disk download

Persistent headless Chromium downloaded 20,000,000,000 bytes into real native OPFS storage. A dedicated worker used synchronous in-place writes, flushing before each IndexedDB checkpoint; it did not reopen/copy the growing prefix for every batch.

- Pause checkpoint: 276,824,064 bytes.
- Same-browser resume checkpoint: 1,082,130,432 bytes.
- Complete browser close/relaunch recovered that exact offset and source version.
- Final disk size and committed offset were both 20,000,000,000 bytes.
- Worker read all bytes back from disk and matched whole-file SHA-256: `oca3kgCmD9udlVcnohSnG2PgVwaD8QOXNqZZOtEGJFY=`.
- Total download, pause/resume, restart and disk verification: **598.258 seconds**.

The source file was the separate transfer-only file with BYPASSED scan status, not the quarantined review object. `storage.persist()` returned false: origin storage was best-effort. The reported quota was 10 GiB, but Chromium wrote all 20 GB without a quota override. Chromium's static reported quota does not necessarily equal its enforced quota ([upstream policy](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/policy/resources/templates/policy_definitions/Miscellaneous/StaticStorageQuotaEnabled.yaml)). This test did not automate external filesystem permission dialogs, test power loss, or prove survival of storage eviction. See [browser recovery](runbooks/large-browser-downloads.md).

## Repeated concurrent workflows

Each file is uploaded, verified/published, downloaded in bounded parallel ranges and independently SHA-256 checked. Per-file upload/download concurrency is three. Durations include fixture preparation, queueing, control-plane requests, verification, and download hashing. Aggregate throughput below is verified bytes divided by full batch wall time, not peak ingress or egress bandwidth. The host runs unrelated services; the two profile families overlap with each other, but the repeated runs started after the large scan and browser jobs completed. These are not controlled before/after or production-capacity measurements.

| Profile | Concurrent files | Bytes per file | Verified / attempted | Batch seconds | End-to-end MiB/s |
|---|---:|---:|---:|---:|---:|
| REQUIRED whole-file ClamAV | 1 | 100,000,000 | 1 / 1 | 7.27 | 13.12 |
| REQUIRED whole-file ClamAV | 4 | 100,000,000 | 4 / 4 | 19.10 | 19.98 |
| REQUIRED whole-file ClamAV | 8 | 100,000,000 | 8 / 8 | 74.39 | 10.26 |
| Transfer-only, scanning bypassed | 1 | 1,000,000,000 | 1 / 1 | 36.13 | 26.39 |
| Transfer-only, scanning bypassed | 4 | 1,000,000,000 | 4 / 4 | 124.77 | 30.57 |
| Transfer-only, scanning bypassed | 8 | 1,000,000,000 | 8 / 8 | 134.30 | 56.81 |

Exploratory results that overlapped the large scan/browser jobs are retained separately. One eight-file run lost its local host API processes and was not completed; it is recorded as interrupted and excluded from successful throughput claims. Test APIs were then detached from command sessions before the repeated eight-file workload.

## Corrections and remaining limits

- Added a bounded two-slot completion executor, preventing one long scan from occupying the only scheduled completion slot; reconciliation skips actively leased completion jobs. This is a code correctness/liveness correction, not a claimed isolated throughput improvement.
- Replaced browser checkpoint prefix copying with native OPFS in-place writes and explicit flush/checkpoint ordering.
- Kept the default scan admission unchanged; opt-in large screening records evidence and requires review. Full malware-cleared 20 GB publication remains unsupported by this ClamAV configuration.
- Single synthetic 20 GB source repeats a seeded 1 MiB block and is compressible. No statistical variance, forced crash/power-failure test, network-loss distribution, multi-tenant fairness study, API p99, or 100,000-session capacity was established.

Raw evidence: [scanner 20 GB](../test-results/scanner-enabled-20gb.json), [normal-download denial](../test-results/scanner-20gb-publication-check.json), [browser 20 GB](../test-results/browser-20gb.json), [whole-file scanner concurrency](../test-results/concurrent-scanner-transfers-final.json), [transfer concurrency](../test-results/concurrent-transfers-final.json), [interrupted attempt](../test-results/concurrent-transfers-interrupted.json), [runtime identity](../test-results/detached-runtime.json).


Final verification: 69 backend tests passed (26 unit, 35 PostgreSQL/S3 integration, 8 real ClamAV integration), zero failures/errors/skips. Seven frontend protocol tests, strict TypeScript and the production Next.js build passed. Repeated concurrency profiles completed all 13 scanner-enabled 100 MB files and all 13 transfer-only 1 GB files with matching downloaded checksums. The eight-file transfer-only row comes from the detached-runtime repeat; its raw result is [final eight-file run](../test-results/concurrent-transfers-final-eight.json).
