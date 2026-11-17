# Browser disk download recovery

The application offers a browser-storage download mode using a dedicated worker and native OPFS createSyncAccessHandle. The worker holds an exclusive native handle, writes fetched batches in place, flushes them, then commits the IndexedDB checkpoint. There is no repeated copy of the growing file prefix. Fetch concurrency is bounded to three 8 MiB ranges; each response must match the version, validator, interval and length contract before the batch is written in order.

Pause aborts outstanding requests and retains the last flushed/checkpointed prefix. Resume truncates uncertain trailing bytes to that prefix. A browser restart releases the exclusive handle, and a new worker reopens the persisted native handle and reconciles its size before proceeding. Final SHA-256 verification reads the disk in bounded chunks; a restart during verification repeats the checksum pass instead of trusting partial hash state.

The real 20,000,000,000-byte Chromium test paused at 276,824,064 bytes, resumed to 1,082,130,432 bytes, closed and relaunched persistent Chromium, recovered the exact checkpoint and finished all disk writes and checksum verification in 598.258 seconds. No quota override or mocked filesystem was used. This was an OPFS test, not external-file-picker permission automation. The downloaded source came from the separate transfer-only profile; the scanner-review 20 GB object remains quarantined.

navigator.storage.persist() returned false in this environment. Browser storage was therefore best-effort and may be evicted. Reported quota was 10 GiB, but writes exceeded that value successfully; recent Chromium intentionally reports a privacy-preserving quota that does not necessarily equal enforcement. Do not reject a download solely on that estimate. Treat quota/write errors as failures and retain only a valid durable checkpoint. Real out-of-disk, eviction, power loss and forced-process-kill scenarios were not tested in this run.

The external File System Access fallback still uses browser-managed writable transactions; only the OPFS worker path was measured at 20 GB. Save important content outside best-effort origin storage. Clear generated test browser profiles and OPFS data only after inspecting the result files.

## Reproduce browser and concurrency tests

Start the local-only harness with HARBOR_RECOVERY_LAB=1, API_ORIGIN=http://localhost:28082 and port 3012; the harness is 404 when not enabled. With an existing verified 20 GB transfer-only object described by `test-results/transfer-20gb-minio.json`, run `npm run test:browser20`. No special quota override is applied. The script creates a temporary persistent browser profile and retains its OPFS data for inspection.

After large jobs have finished, run `python3 benchmarks/concurrent_transfers.py --label final` for the transfer-only profiles and `python3 benchmarks/concurrent_transfers.py --scanner --label final` for REQUIRED whole-file scanning profiles. Reference test ports are 28082 and 28083 respectively. Start long-lived APIs independently of command-session lifetimes; use immutable JAR copies. Concurrent output JSON contains exact per-file verdicts, checksums, retries and end-to-end timings. Avoid interpreting overlapping workloads as controlled before/after evidence.
