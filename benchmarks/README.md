# Reproducible measurement protocol

No performance results are asserted. Integration-test timing is not a benchmark. Run `metadata.js` with RATE 1000,
10000, 100000 and sync.js with DEVICES 1000, 10000, 100000 on isolated generators. Initial scripts use a single identity
and are intentionally insufficient for representative global scale: seed many namespaces/tenants with realistic
hierarchy/skew and assign credential pools before scale claims. A 1M delivery/sec test needs a write driver and explicit
subscriber fan-out; this release has no push transport to benchmark.

Record revision/dependency tree, OS/JVM, API pods and CPU/memory, DB topology and cache state, storage region, network
path, seeded row sizes/counts, generator CPU, offered and achieved rates, dropped_iterations, p50/95/99, errors, lock
time, DB CPU/IO/WAL, Hikari active/pending, object API latency/bandwidth, JVM allocation/heap/GC, stream backlog and
recovery interval. Redis hit rate and Kafka lag are not applicable to the current implementation.

Warm up 5min, measure 15min, cool down, repeat at least three times and publish raw JSON/Prometheus snapshots, not only
averages. k6 thresholds use the target p99<50ms; failures mean the target was not met, not that the script is wrong. A
localhost functional transfer does not measure production bandwidth or multi-GB behavior.

Transfer matrix: 1KiB, 10MiB, 1GiB, 5GiB immutable files using docker/transfer.py, 1/10/100/1000 concurrent sessions,
re-run session state after process kill, concurrently GET authorized URLs with Range headers. The harness is a
correctness driver, not a calibrated high-rate generator. For hot/cold downloads vary CDN cache state and origin key
distributions. For metadata seed a million-child folder and hierarchy depth 1/10/100; evaluate query plans and lock
saturation. Introduce storage latency/timeout and DB failover in a dedicated chaos environment; record actual
acknowledged writes before and after recovery.

## Bounded-memory transfer driver

```sh
# DRIVE_API ends in /api/v1; DRIVE_AUTH is an authorized disposable identity.
python3 benchmarks/transfers.py /absolute/path/to/immutable-sample.bin --iterations 10 --workers 2 > result.json
```

Run separately for 0 bytes, 1 KiB, 10 MiB, 1 GiB and 5 GiB. Increase concurrency only with a storage/quota budget. Each
iteration creates a unique retained file and reports upload time (hashing, URL issuance, multipart PUTs, final
verification/publication), download authorization time, streaming verified GET time, one-byte Range time, errors and
samples. The signed URL is renewed before Range, so long downloads do not reuse expired authorization. Failures exit
nonzero; output excludes secrets and internal identifiers. Upload subprocess buffers one part; downloads read 128 KiB at
a time. Connection establishment and subprocess costs are included. Percentiles are nearest-rank and meaningless for
small sample counts; a single functional sample is not a benchmark result. Account cleanup is an operator responsibility
because trash does not free retained-version quota.

The integration suite executes the driver once for empty content and once for a small payload, checking successful JSON
reports and Range behavior. No representative large-file or high-concurrency capacity measurements are asserted.
