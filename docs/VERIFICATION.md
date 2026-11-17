# Verification record — 2026-11-17

The initial foundation record below is historical. The continuation record at the end supersedes its outstanding items for completion, downloads and media processing.

Executed with Java 21.0.13, Spring Boot 4.1.2 and PostgreSQL 17 / LocalStack S3 through Testcontainers.

- Backend packages into an executable JAR.
- 19 backend unit tests passed across eight suites.
- All 33 transfer integration tests passed: 87.21 seconds, zero failures/errors/skips. Includes multipart completion, duplicate completion, manifest checks, range bytes/206, cross-owner access, quotas, interrupted sessions, cleanup/reconciliation, version retention, real Java/Python recovery clients, schema upgrades, supplemental processing execution and an explicit stale-fence conditional-write assertion.
- All 10 application migrations executed on fresh PostgreSQL.
- `npm run build` passed with strict TypeScript enabled.
- `npm run typecheck` passed.
- Three Node transfer tests passed: known SHA-256 vector, bounded three-worker pool, cancellation prevents completion.
- Built Next.js server ran at localhost:3011. A headless Chromium smoke test verified heading rendering, unavailable-backend feedback, and rendered a 390 × 844 mobile screenshot. This was not an authenticated browser upload test or accessibility audit. The backend was not running during this smoke check, so the UI displayed HTTP 500 from the proxy.

Commands, from the project root:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml -Pintegration -Dtest=IntegrationTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml -Dmaven.test.skip=true package
cd frontend
npm run build
npm run typecheck
npm test
```

Early attempts failed due to sandboxed Maven locks, Docker socket access, Mockito agent attachment, missing Boot 4 Flyway auto-configuration, Jackson 3 API migration, foreign-key retention constraints, and relocated scripts. These were addressed before the successful runs above. The real ClamAV integration suite has not been confirmed successful for this project; scanner unit tests use synthetic streams.

## Benchmark evidence

No 1 GB / 5 GB / 20 GB, high-concurrency, network-failure performance, p99 latency, or cost benchmark results are claimed. The Python harness is available and its small fixture invocation passed within integration testing. Test elapsed time is not a throughput measurement.

## Outstanding acceptance work

- Production isolation and independent deployment of durable completion/scanning workers.
- Isolated parser containers, PDF previews and video transcoding beyond the implemented PNG, UTF-8 and ZIP processors.
- Parallel range scheduling and real-browser restart/permission testing beyond the implemented sequential disk-backed resume.
- Dedicated dashboard, processing/admin/sharing pages; browser OIDC login; multi-file transfer queue; per-byte upload speed/ETA.
- Kafka transactional outbox/inbox if external event integration is introduced.
- Full OpenAPI contracts, all 45 structured failure analyses, and expanded 40-question architecture review.
- Worker crash/lease tests with independently terminated processes; browser refresh/interruption end-to-end tests; automated accessibility audit.
- Measured 20 GB and concurrency workloads, fair admission limits, database scale testing.
- Validated Compose deployment, Kubernetes rollout, Helm/Terraform, AWS deployment, backups/restores, multi-AZ/CDN tests.
- Security review, scanner operational sizing, immutable storage policy, provider-version fencing, API rate limiting and production readiness review.

Compose/Kubernetes/CI definitions are source artifacts, not evidence of a completed deployment. Existing unrelated projects were preserved.

## Continuation: durable completion, download resume and media

Implemented HTTP 202 durable completion intent, renewable/fenced completion leases, verification and mandatory scanning outside database locks, durable terminal failure status and review quarantine. Java/Python/static/Next.js clients understand asynchronous completion. PostgreSQL migrations V11–V14 add lease, artifact and cleanup ownership records.

Implemented managed sequential 8 MiB range downloads to File System Access destinations with IndexedDB checkpoints, explicit source version/SHA-256 pinning, strong ETag/If-Range checks, exact 206/Content-Range/body-length validation, and final streamed disk checksum verification. Native fallback remains browser-managed. The frontend exposes pause, resume and checkpoint reset.

Implemented PNG thumbnails, UTF-8 document metadata and bounded ZIP inventory. Artifact registration is lease-fenced, registered for garbage collection before PUT and authorized against the current file version. Added processing/artifact UI actions and a [recovery runbook](runbooks/completion-download-processing.md).

- Six frontend protocol tests pass, including resumed disk writes and rejection of changed source/range validators.
- Strict TypeScript and Next.js production build pass.
- Four focused PostgreSQL/S3 integration cases pass: async nonblocking completion/fencing, artifact integrity, logical conflict preservation and restart recovery.
- Eight real ClamAV integration cases pass (56.48 seconds), including the updated HTTP 202 → eventual quarantine contract, scanner outage behavior and forbidden publication of rejected bytes.

No 20 GB, high-concurrency or disk checkpoint performance claim is made. Actual process termination/restart tests, native browser filesystem permission/restart flows and process-isolated media parsing remain further verification work. Manual version-rescan APIs inherited from the original service still perform synchronous inspection; initial upload verification/scanning uses the durable worker.

Final phase regression result: 23 backend unit tests + 34 PostgreSQL/S3 integration tests (132.1 seconds) + 8 real ClamAV integration tests (56.48 seconds) = **65 backend tests passed**, no failures/errors/skips. The two integration runs were executed separately. Backend `verify` packaged the executable JAR with all 14 migrations. Six frontend protocol tests and the strict Next.js production build passed.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml -Pintegration '-Dtest=!MalwareIntegrationTest' verify
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml -Pintegration -Dtest=MalwareIntegrationTest test
cd frontend
npm test
npm run typecheck
npm run build
```

Regression fixes included asynchronous recovery-client polling, durable classification of database uniqueness conflicts, namespace-lock-free verification, expected HTTP 202 quarantine behavior, source-version retention, and orphan artifact cleanup ownership. Earlier intermediate regression failures are superseded by the successful final runs above.


## Parallel downloads, sandboxed previews, browser restart and large-file measurements

Final all-backend regression run: **66 tests passed** (23 unit tests, 35 PostgreSQL/S3 integration tests, 8 real ClamAV integration tests), zero failures/errors/skips. The transfer suite took 117.3 seconds; ClamAV suite 38.72 seconds. Seven frontend protocol tests pass, including three-way out-of-order fetch completion with ordered disk writes. Three sandbox HTTP rejection tests pass. The production Next.js build passes.

Real persistent Chromium closed and relaunched successfully during both a 40 MiB multipart upload and an OPFS-backed parallel download; provider parts and the 24 MiB IndexedDB checkpoint survived. The final downloaded checksum matched. Sandbox PDF first-page and MP4 first-frame processing passed scanner-enabled end-to-end tests.

A complete **20,000,000,000-byte MinIO transfer** passed upload, server verification/promotion, independent parallel download SHA-256 validation and durable inspection. It used the explicitly scan-bypassed local profile. The REQUIRED policy separately returned 413 for 20 GB. The raw successful, interrupted and provider-failed attempts are preserved. See [measured results](MEASURED-RESULTS.md) for exact times, throughput, heap samples, defects, environment and limitations.

Current remaining risks include full 20 GB browser disk-write performance, real external-file permission prompt automation, process-kill crash tests, large-file mandatory scanning support, full video transcoding, workload repetition/variance, production sandbox deployment, and scale/SLO validation. Native OPFS restart tests do not claim these capabilities.


## Scanner-enabled 20 GB review, real browser 20 GB and concurrent repeats

69 backend tests passed with zero failures/errors/skips, including three new segment coverage/boundary/fail-closed tests. Seven frontend protocol tests, TypeScript checking and production build passed. Real Chromium OPFS download, pause/resume and full browser restart passed for exactly 20,000,000,000 bytes, including a complete disk SHA-256 pass (598.258 seconds total).

REQUIRED scanner-enabled 20 GB upload covered all bytes through 598 overlapping signature scans and correctly ended QUARANTINED / REVIEW_REQUIRED; it created no normal downloadable version (HTTP 404). This is not full-file malware clearance. Repeated 1/4/8-file whole-file-ClamAV 100 MB workflows all passed, as did repeated transfer-only 1/4/8-file 1 GB workflows. An API-process interruption was recorded separately and its unfinished eight-file attempt excluded; the detached-runtime repeat passed.

[Detailed measured results and limitations](MEASURED-SCANNER-BROWSER-CONCURRENCY.md), [large-scan policy](runbooks/large-scan-review.md), [native browser disk recovery](runbooks/large-browser-downloads.md).

## Management, admission and full short-video continuation

The final backend regression run passed **73 tests across 13 suites**, zero failures/errors/skips, with 154.392 summed suite-seconds. The command was `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml -Pintegration test`. This includes 38 main integration tests and eight real ClamAV tests. Packaging passed, and the immutable packaged runtime validated all 17 Flyway migrations on the existing isolated workspace database. [Machine-readable result](../test-results/backend-functional-regression.json).

New assertions cover tenant administrator roles and cross-tenant denial, quota compare-and-set, durable admission/replay accounting, share-link revocation, unauthorized cross-owner sharing/reprocessing and stale-fence artifact denial. The sharing regression exposed a central authorization defect: namespace-owner privileges previously returned before confirming that the requested node belonged to that namespace. The common access method now validates that relationship first.

Next.js type checking, production build and seven Node protocol tests passed. Real Chromium management checks passed against the production build: create/rename/delete folders, keyboard Escape from the native confirmation dialog, in-memory credential continuity during navigation, ordinary-user admin denial, admin usage/reconciliation and the live OpenAPI inventory. A 390×844 screenshot was inspected. This is not a comprehensive accessibility audit. [Browser report](../test-results/workspace-browser.json).

A fresh **40 MiB** browser test against the production build and scanner-enabled API passed interrupted-upload recovery across a full Chromium restart (two provider parts retained), then parallel-download recovery across another restart from a **24 MiB** disk checkpoint, followed by matching whole-file SHA-256. [Recovery result](../test-results/browser-restart-functional.json). The first production-origin attempt failed because benchmark MinIO CORS did not allow port 3015: accepted provider parts alone did not establish browser transfer success. The harness now rejects incompatible CORS origins before transferring, reports browser UI errors and uses unique fixture names. The successful run used authorized origin localhost:3011. Failed test sessions were explicitly aborted to release reservations.

The required-scanner media harness passed with a 4,571-byte synthetic MP4: scan status CLEAN, durable attempt SUCCEEDED, preview plus full video-transcoded variants, 2.000-second source and output duration, H.264 640×480, 2,313-byte artifact and matching downloaded SHA-256. Total harness time was **5.105654792 seconds**, including fresh upload, scanning, processing, credential retrieval and external source/output probes. This is a synthetic correctness measurement, not a media throughput benchmark. Three live broker rejection tests also passed. [Media result](../test-results/media-verification.json), [reproducible harness](../sandbox/verify_media.py).

The generated authenticated OpenAPI inventory contains 50 paths; response schemas remain generic. CI is configured to run the full integration suite including real ClamAV, frontend checks/build and upload backend test reports. Hosted CI has not been run. The earlier 20 GB and 1/4/8 concurrency measurements were not repeated for this management/processing continuation. See [current acceptance gaps](IMPLEMENTATION-STATUS.md).
