# Harbor large-file transfer platform

Java 21 / Spring Boot 4.1.2 / Jackson 3 backend and Next.js 16.4.6 / React / TypeScript / Tailwind 4 frontend. Derived from this repository's drive-clone without modifying that project. See the [current implementation and remaining requirements](docs/IMPLEMENTATION-STATUS.md).

Direct S3 multipart transfer keeps file bodies off application request paths. PostgreSQL owns immutable versions, upload sessions, quotas, completion leases and processing jobs. Completion returns 202; availability requires independent streaming size/SHA-256 verification and the mandatory scan decision. Signed download credentials require authorization and a downloadable version.

## Build and test

Run from this directory:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -f backend/pom.xml -Pintegration verify
cd frontend
npm ci
npm run typecheck
npm test
npm run build
```

Integration tests need Docker and include real PostgreSQL, S3-compatible storage and ClamAV containers. The latest full backend run passed 73 tests, with no skips or failures. Seven frontend protocol tests, the Next.js production build and authenticated management browser checks passed. Hosted CI execution remains unverified.

## Local deployment

```sh
export DATABASE_PASSWORD=local-database-secret
export LOCAL_PASSWORD=local-workspace-password
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export SCAN_MODE=REQUIRED
docker compose --profile antivirus up --build -d
```

Open http://localhost:3011 after the scanner has loaded signatures. The local UI accepts `Basic <base64(username:password)>` in its Authorization field. Development users are alice, bob, charlie and admin; only admin has the administration role. Credentials stay in memory across client navigation. Production uses OIDC issuer/audience/tenant validation and a trusted `tenant_admin` role claim. Local Basic authentication must remain private.

The default local Compose storage service is LocalStack despite its historical service name `minio`. Use the separately documented real MinIO benchmark profile for large transfers; the LocalStack 20 GB attempt failed. The PDF/video sandbox broker is a separate trusted local component; follow [sandbox setup](sandbox/README.md). Compose alone does not enable that broker.

## Working UI and protocols

The navigation provides dashboard, file explorer, upload history, download list, processing jobs/artifacts, sharing and tenant administration. The Transfers page performs bounded parallel uploads and managed downloads. Resume an upload by reconnecting and reselecting the original file; provider state is authoritative. File deletion uses revision checks and a keyboard-accessible confirmation dialog. Admin quota changes use compare-and-set updates; reconciliation requests are durable, leased and tenant-scoped.

Managed downloads support three parallel ranges and ordered disk commits with version, size and validator checks. Chromium OPFS uses bounded worker buffers and durable checkpoints. Native file destinations require File System Access permission. Unsupported browsers use native streaming downloads with browser-managed recovery. Downloads are verified using the recorded whole-file SHA-256. Expiring signed URLs remain usable until expiration even after sharing is revoked.

Upload creation uses `POST /api/v1/uploads` with Idempotency-Key and `{name,size,sha256,expected:0}`. Part signing uses `POST /uploads/{id}/parts` with `{number,sha256}`. Complete and abort are POST operations. Poll completion until COMPLETED, QUARANTINED or ABORTED. The live authenticated route/request inventory is `/api/v1/openapi.json`; endpoint-specific response schemas remain incomplete.

## Measured results and limits

The [raw evidence and measured-results report](docs/MEASURED-SCANNER-BROWSER-CONCURRENCY.md) includes a real Chromium 20 GB OPFS download/pause/resume/restart with disk checksum verification in 598.258 seconds, scanner-enabled 20 GB ingestion that correctly remains review-quarantined, and repeated 1/4/8 concurrent transfer workloads. These earlier measurements were not rerun for the new management pages.

The latest real scanner-enabled synthetic video check preserved the complete two-second duration, generated 640×480 H.264 output and verified its downloaded checksum. Full video transcoding is limited to 64 MiB input, 120 seconds and 8 MiB output in a restricted local Docker sandbox. PDF processing renders the first page. Image thumbnails, text metadata and bounded ZIP inventory are also implemented.

This is not a production-readiness declaration. Full-file 20 GB ClamAV clearance, production sandbox deployment, browser OIDC UX, complete API contracts, Kafka integration, AWS deployment and scale/SLO certification remain open. See [status](docs/IMPLEMENTATION-STATUS.md), [verification history](docs/VERIFICATION.md), [architecture](docs/ARCHITECTURE.md), and [benchmark reproduction](docs/runbooks/measured-local-benchmark.md).
