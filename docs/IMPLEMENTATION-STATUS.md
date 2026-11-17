# Implementation status — 17 November 2026

This is a working local platform with measured transfer and recovery evidence. The full master-prompt acceptance criteria are not complete. Historical verification documents retain failed attempts and older limitations; this matrix describes the current implementation.

| Area | Implemented | Evidence and limits |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1.2, JDBC/Flyway, OIDC resource server, local development Basic authentication | Executable JAR packages; 73 tests across 13 suites passed, zero skipped/errors/failures. No live production identity-provider exercise. |
| Multipart uploads | Direct private storage transfer, per-part SHA-256, bounded browser workers, authoritative reconciliation, idempotency, durable completion leases and heartbeats | PostgreSQL/storage integration and browser restart tests. S3 ETags are validators, never whole-file MD5 proof. |
| Integrity and scanning | Independent streaming whole-file SHA-256 and length verification; private quarantine; real ClamAV; durable scan evidence | Whole-file scanning admitted through 100 MiB. Opt-in 20 GB segmented signature screening mandates REVIEW_REQUIRED quarantine and cannot approve downloads. |
| Admission and quota | Durable namespace reservation/commit/release, concurrent-session cap and per-minute session admission; idempotent replays do not consume another admission | Contention and admission integration tests. Tenant aggregate quotas, daily bandwidth accounting and full endpoint rate limiting remain open. |
| Processing | Durable claims, renewable leases, fenced artifact promotion, attempt history, source/pipeline identity, retries, cancellation and authorized reprocessing | Image thumbnails, UTF-8 metadata, bounded ZIP inventory, sandboxed PDF first-page/video first-frame output and full short-video H.264/AAC transcode. CPU-heavy image/archive work remains in-process. |
| Media isolation | Authenticated loopback broker, fresh non-root/no-network Docker containers, bounded CPU/memory/PIDs/tmpfs/time/output | Real scanner-enabled two-second MP4 produced a matching-duration 640×480 H.264 artifact; downloaded SHA-256 verified. Input cap 64 MiB, video cap 120 seconds, output cap 8 MiB. Not a production Kubernetes sandbox service. |
| Downloads | Authorized expiring credentials, version and checksum identity, native storage ranges, bounded parallel browser ranges, checkpointed OPFS and File System Access destinations | Previously measured real Chromium 20 GB pause/resume/restart and disk SHA-256 passed. Native external picker permissions and power-loss recovery remain separate unverified cases. |
| File management | Folders, search, rename, revision-aware deletion, immutable history, owner sharing/permissions and expiring/revocable links | Real-API browser create/rename/delete and keyboard dismissal passed. Central ownership check now validates node namespace before owner authorization. |
| Next.js pages | Transfers, dashboard, files, uploads, downloads, processing, sharing and administration use real APIs | Production build, TypeScript and seven protocol tests pass. Managed transfers remain on the main Transfers page. No browser OIDC login UX, multi-file queue, inline media preview or full WCAG audit yet. |
| Administration | Tenant-scoped role checks, usage, CAS quota updates, audit/quarantine/upload/job views and durable leased reconciliation requests | Integration and authenticated browser tests. No unsafe manual quarantine override is provided. |
| API contracts | Live authenticated OpenAPI 3.1.1 route/parameter/request-schema inventory; Problem Details and Retry-After for admission rejection | Response schemas are generic and must become endpoint-specific before claiming complete OpenAPI contracts. |
| Operations | Compose, Kubernetes reference manifests, metrics, cleanup/reconciliation and CI workflow | Local tests executed. CI is configured to include real ClamAV tests; the hosted workflow has not been executed in this session. No AWS deployment/restore/failover certification. |

## Evidence

- [Backend regression counts](../test-results/backend-functional-regression.json)
- [Real management browser checks](../test-results/workspace-browser.json)
- [Scanner-enabled full video transcode verification](../test-results/media-verification.json)
- [20 GB and concurrent transfer measurements](MEASURED-SCANNER-BROWSER-CONCURRENCY.md)
- [Sandbox deployment and reproduction](../sandbox/README.md)

The media verification elapsed time includes local upload, mandatory scanning, processing, artifact download and two ffprobe container invocations. It is a synthetic correctness check, not a video throughput benchmark. The prior 20 GB and concurrency results have not been rerun for these management-page changes.

## Remaining production work

Full-file 20 GB malware clearance needs an engine/contract capable of that size. Segmented screening cannot substitute for container-aware scanning. Build browser OIDC login, a multi-file transfer queue, complete endpoint-specific response schemas and event-stream contracts, tenant-level admission fairness, retention/legal-hold administration, sandboxed image/archive processing and durable manual-review workflows. PostgreSQL currently owns the durable work queue; Kafka/outbox/inbox integration has not been implemented or claimed.

Operational acceptance still requires real process-kill/power-loss recovery, backup/restore and rolling-upgrade drills, signature freshness management, adversarial parser validation, a production worker-isolation design, deployment/rollback automation, managed AWS infrastructure and CDN authorization tests. The 100,000-session target, control-plane p99, 99.99% availability, multi-region recovery and workload costs remain unverified. The full 45-scenario and 40-question documentation set is also incomplete.
