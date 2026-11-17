````
# FINAL MASTER PROMPT
# Design, Develop, Test, Benchmark, and Deploy an Enterprise-Grade High-Concurrency Large File Upload, Download, and Asynchronous Processing Platform

**Backend:** Java 21 + Spring Boot 4.x

**Frontend:** Next.js + React + TypeScript + Tailwind CSS

**Core Capabilities:** Chunking, Multipart Upload, Resumable Upload, Resumable Download, Parallel Transfers, Async Processing, Validation, Deduplication, and Recovery

**Infrastructure:** PostgreSQL, Redis, Apache Kafka, S3-Compatible Object Storage, CDN, Docker, Kubernetes, AWS

**Architecture:** DDD, SOLID, Clean/Hexagonal Architecture, Event-Driven Processing, Pragmatic Microservices, and All Six AWS Well-Architected Pillars

---

# 1. ROLE AND ENGINEERING MISSION

Act as an enterprise engineering team consisting of:

- Principal Software Architect
- Distinguished Distributed Systems Engineer
- Java 21 and Spring Boot 4.x Architect
- High-Performance Networking Engineer
- Object Storage and Distributed Filesystems Architect
- Data Integrity and Security Engineer
- Asynchronous Processing Architect
- PostgreSQL and Kafka Specialist
- Next.js and React Architect
- Senior Product/UI/UX Designer
- AWS Solutions Architect
- Site Reliability Engineer
- Quality Engineering Lead

Your task is to **design, develop, test, benchmark, secure, document, and deploy a complete High-Concurrency Large File Upload, Download, Storage, and Processing Platform**.

The platform must support realistic use cases such as:

- Cloud drive and file sharing
- Enterprise document management
- Video and media ingestion
- Large dataset uploads
- Data migration
- Backup and restore
- User-generated content
- Batch document processing

Treat this as a real enterprise engineering execution assignment, not merely a system design exercise.

**Do not stop at HLD, LLD, pseudocode, sample APIs, architecture diagrams, or static UI mockups. Deliver working backend services, functional Next.js frontend, reliable file-transfer protocols, asynchronous workers, automated tests, benchmarks, infrastructure, and documentation.**

## Mandatory engineering rules

1. Use Java 21 and a verified compatible Spring Boot 4.x release.
2. Follow existing repository instructions, dependency versions, and coding conventions.
3. Account for Spring Framework 7, Jakarta EE 11, Jackson 3, modular starters, and Spring Boot 4 API changes.
4. Use compatible stable Next.js App Router and TypeScript versions.
5. Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
6. Keep transfer data-plane responsibilities separate from the file-management control plane.
7. Prefer direct browser-to-object-storage transfer for large files.
8. Avoid buffering entire files in Spring Boot heap memory.
9. Use bounded concurrency, connection pools, chunk sizes, retries, queues, disk, and memory.
10. Design uploads/downloads to recover after network interruption, browser restart, server restart, and worker failure.
11. Never treat a client-reported upload completion as proof of complete, valid, safe content.
12. Enforce file ownership, upload scope, authorization, and object-key validation.
13. Treat all uploaded files as untrusted.
14. Prevent duplicate logical files/jobs when a client retries.
15. Define checksums, chunk identities, byte ranges, ETags, and object versioning precisely.
16. Do not assume S3 multipart ETags are universally whole-file MD5 checksums.
17. Never use `synchronized` alone to coordinate distributed transfers.
18. Do not rely solely on Redis for durable upload-session correctness.
19. Implement idempotent processing and version-aware job transitions.
20. Do not invent dependencies, benchmarks, test outcomes, or production-readiness claims.
21. Preserve unrelated work.
22. Implement incrementally and verify each working vertical slice.

---

# PART A — PRODUCT OBJECTIVE

## 2. PRIMARY CAPABILITIES

Build an enterprise platform supporting:

1. User authentication
2. File and folder management
3. Single-file upload
4. Large multipart upload
5. Client-side chunking
6. Parallel part uploads
7. Pause/resume/cancel
8. Interrupted-upload recovery
9. Browser refresh recovery
10. Batch upload
11. Upload progress
12. Server-side integrity validation
13. Media/document validation
14. Asynchronous processing
15. Background job status
16. Processing retries
17. Processed artifact generation
18. Secure downloads
19. Resumable downloads
20. HTTP byte-range downloads
21. Parallel range downloads
22. Download progress
23. Download pause/resume
24. File versioning
25. File metadata
26. Deduplication where justified
27. Sharing and access permissions
28. File expiry and retention
29. Virus/malware screening
30. Quotas and rate limiting
31. Search and filtering
32. Audit logging
33. Admin operations
34. Metrics, logs, and traces
35. Docker/Kubernetes deployment
36. AWS reference architecture

---

# PART B — END-TO-END SCENARIO

## 3. UPLOAD A 20 GB VIDEO

A user selects a 20 GB video using the Next.js interface.

The system must:

1. Authenticate the user.
2. Validate tenant/project and available storage quota.
3. Validate filename, size, extension, and declared content type.
4. Create an idempotent upload session.
5. Allocate a private temporary object key.
6. Initiate an S3-compatible multipart upload.
7. Select a supported chunk size and concurrency.
8. Generate time-limited upload URLs for authorized part numbers.
9. Upload multiple chunks concurrently.
10. Track completed parts and their returned metadata.
11. Retry only failed or uncertain parts according to protocol semantics.
12. Allow pause, resume, and cancellation.
13. Recover after browser refresh using the server-side upload session.
14. Complete the multipart upload only after validating the part manifest and expected size.
15. Confirm object metadata and storage ownership.
16. Validate content integrity.
17. Move or promote the object into the appropriate quarantine/processing lifecycle.
18. Trigger asynchronous processing.
19. Track validation, scanning, transcoding, and artifact generation.
20. Mark content AVAILABLE only after all mandatory checks pass.
21. Allow secure download.
22. Support HTTP Range requests and resumable downloads.
23. Record audit and operational metrics.
24. Reconcile orphaned parts, incomplete sessions, and failed jobs.

### Fundamental correctness invariants

- An upload session belongs to one authenticated owner and immutable authorized target scope.
- Concurrent duplicate completion requests cannot create multiple logical finalized files.
- A completed file must reference an object proven to exist with verified expected properties.
- No failed, quarantined, deleted, or unauthorized file may be served through normal download APIs.
- A processing job cannot promote the wrong file version.
- Download permissions must be enforced before issuing a direct-download credential.
- Retries and failovers must not corrupt byte ranges or file contents.
- Storage quotas must have a defined reservation and release model.
- Every processing state and externally visible artifact must be recoverable or explicitly classified as terminally failed.

---

# PART C — NON-FUNCTIONAL REQUIREMENTS

## 4. DESIGN TARGETS

Treat these as architectural targets requiring validation.

| Dimension | Design Target |
|---|---|
| Maximum tested upload size | 20 GB initial scenario |
| Larger file capability | Provider-supported, policy-defined |
| Concurrent upload sessions | 100,000+ architectural target |
| Concurrent active downloads | 100,000+ architectural target |
| File metadata corpus | 100 million+ records |
| Parallel chunks per upload | Adaptive and bounded |
| API processing latency | p99 below 100 ms for defined control-plane calls |
| Upload data durability | Defined by object-storage replication and verification |
| Availability | 99.99% design target |
| Resume correctness | No verified bytes lost or duplicated under supported failure cases |
| Object storage | S3-compatible |
| Deployment | Multi-AZ Kubernetes |
| Multi-region | Optional extension |

Distinguish:

- Upload session throughput
- Active transfer connections
- Uploaded bytes/sec
- Completed files/sec
- Processing jobs/sec
- Download bytes/sec
- Download requests/sec
- Metadata query throughput

Do not assume API RPS and object-storage data throughput are interchangeable.

---

# PART D — HIGH-LEVEL ARCHITECTURE

## 5. COMPARE ARCHITECTURAL OPTIONS

### Option A — Spring Boot Proxy Upload

Client sends entire file through the application.

Evaluate memory, network bandwidth, scaling, and operational risk.

### Option B — Direct S3-Compatible Upload

Spring Boot creates upload sessions and signed object-storage requests.

Client transfers bytes directly to storage.

### Option C — Multipart Upload + Async Processing

Direct multipart upload, durable metadata, validation workers, and event-driven processing.

### Option D — Globally Distributed Transfer Platform

Regional upload endpoints, multi-region storage, CDN, transfer acceleration, regional processing, and global metadata ownership.

Compare latency, throughput, security, cost, failure recovery, and complexity.

**Recommend Option C for the working implementation, with a documented path toward Option D.**

---

## 6. REFERENCE COMPONENTS

Design:

1. Identity and Access
2. File Management API
3. Upload Session Manager
4. Multipart Upload Coordinator
5. Storage Provider Adapter
6. Upload Part Manifest
7. Upload Integrity Validator
8. File Metadata Service
9. Quota Manager
10. Malware/Content Scanner
11. Async Processing Orchestrator
12. Processing Workers
13. Artifact Registry
14. Download Authorization
15. Range Download Support
16. CDN Integration
17. Retention and Cleanup Workers
18. Reconciliation Engine
19. PostgreSQL
20. Redis
21. Kafka
22. S3-Compatible Object Storage
23. Next.js Frontend
24. Observability

Provide Mermaid diagrams for upload, resume, completion, processing, downloading, failure recovery, and deployment.

---

# PART E — MULTIPART UPLOADS

## 7. UPLOAD PROTOCOL

Implement S3-compatible multipart upload as the baseline.

Workflow:

1. Initiate upload.
2. Create durable session.
3. Issue scoped pre-signed part URLs.
4. Upload parts.
5. Track provider-returned part metadata.
6. Reconcile uploaded parts when needed.
7. Validate manifest.
8. Complete multipart upload.
9. Verify final object.
10. Finalize logical file record.

Never use a client-submitted ETag as cryptographic proof of whole-file integrity.

---

## 8. CHUNKING

Implement configurable chunk sizing.

Evaluate:

- Small chunks: lower retry cost, more requests
- Large chunks: fewer requests, higher retry cost
- Adaptive chunk sizing
- Browser memory
- File size
- Connection quality
- Provider restrictions

Follow documented S3 multipart limits, including minimum nonfinal-part size, maximum number of parts, and valid part numbers.

Do not invent fixed chunk sizes that violate storage-provider constraints.

---

## 9. PARALLEL UPLOADS

Implement:

- Configurable upload concurrency
- Per-file concurrency cap
- Global browser transfer cap
- Retry budget
- Rate limiting
- Browser memory bounds
- Adaptive concurrency where justified

Do not create an unbounded Promise array containing all chunks and launch them simultaneously.

Use a bounded worker pool.

---

## 10. UPLOAD STATE MACHINE

Suggested states:

- INITIATED
- UPLOADING
- PAUSED
- COMPLETING
- UPLOADED
- VALIDATING
- PROCESSING
- AVAILABLE
- FAILED
- CANCELLED
- EXPIRED
- QUARANTINED

Choose legal transitions.

Use versioned conditional database updates.

Separate upload-session state from downstream processing-job state.

---

# PART F — RESUMABLE UPLOADS

## 11. RESUME AFTER INTERRUPTION

Support:

- Lost network
- Browser refresh
- Browser restart
- Expired signed URL
- Temporary object storage failure
- Backend restart
- Duplicate part response
- Unknown part result

The client must recover the authoritative upload manifest from the backend/object storage instead of assuming locally stored progress is correct.

---

## 12. PROTOCOL COMPARISON: S3 MULTIPART VS TUS

Compare:

### S3 Multipart

- Independent part uploads
- Provider upload ID
- Part numbers
- Part ETags/checksums
- Complete/Abort operations
- Direct-to-storage transfer

### tus 1.0

- Upload resource
- Offset-based resume
- HEAD for current offset
- PATCH for sequential byte continuation
- Checksum and termination extensions where supported
- Concatenation extension for parallelism where supported

Select S3 multipart for the core implementation.

If tus is implemented, use a separate compatible endpoint and explicitly support its required wire semantics.

Do not claim an ordinary S3 multipart endpoint implements tus.

---

## 13. DUPLICATE AND OUT-OF-ORDER PARTS

Handle:

- Duplicate part number
- Reuploaded part
- Missing part
- Part manifest mismatch
- Client-side checksum mismatch
- Server-side checksum mismatch
- Out-of-order completion
- Completion racing with cancellation
- Upload expiration

Do not finalize a file unless its authorized part manifest is consistent with provider state.

---

# PART G — CHECKSUMS AND DATA INTEGRITY

## 14. CHECKSUM STRATEGY

Evaluate:

- SHA-256
- Storage-provider native checksums
- Per-part checksums
- Whole-file checksums
- Multipart composite checksums
- Content fingerprinting

Define:

- What is calculated by the browser
- What is checked by object storage
- What the backend independently verifies
- What is stored as metadata
- When the file is considered verified

Avoid claiming that multipart ETags always equal MD5 hashes.

---

## 15. END-TO-END VERIFICATION

Implement:

- Expected file size validation
- Object existence validation
- Chunk manifest validation
- File integrity verification
- Final content-type validation
- Processing output validation
- Corruption detection
- Audit evidence

For large files, avoid loading the complete object into JVM memory merely to calculate a checksum.

Use streaming verification or provider-supported checksums when their semantics satisfy the contract.

---

# PART H — DURABLE METADATA

## 16. POSTGRESQL MODEL

Design tables such as:

- files
- file_versions
- file_metadata
- upload_sessions
- upload_parts
- upload_idempotency
- storage_objects
- processing_jobs
- processing_attempts
- processed_artifacts
- download_tokens
- download_audit
- folders
- file_permissions
- share_links
- quota_accounts
- quota_reservations
- storage_movements
- cleanup_jobs
- reconciliation_jobs
- outbox_events
- processed_events
- audit_events

Use Flyway.

Define constraints, indexes, ownership, versioning, and lifecycle rules.

Do not store large file bodies in PostgreSQL.

---

## 17. FILE VERSIONING

Support:

- File ID
- Version ID
- Object key
- Object version
- Content length
- Checksum
- MIME type
- Owner
- Created timestamp
- Processing status
- Current version pointer

Updates must not accidentally overwrite previously committed versions.

Separate logical file identity from immutable object version identity.

---

# PART I — QUOTAS AND ADMISSION CONTROL

## 18. STORAGE QUOTAS

Implement:

- Per-user quota
- Per-tenant quota
- Maximum file size
- Maximum concurrent uploads
- Daily transfer quota where justified
- Processing quotas
- Download rate limits

Define:

- Quota reservation before upload
- Actual usage commitment after verification
- Reservation expiration
- Aborted-upload release
- Concurrent quota checks
- Cleanup reconciliation

Use atomic database operations for hard quota guarantees.

Redis may optimize admission but should not be the sole durable source for strict storage accounting.

---

## 19. BACKPRESSURE

When system capacity is exhausted:

- Reject or defer new sessions
- Return appropriate 429/503 responses
- Apply per-tenant fairness
- Bound processing backlog
- Prevent unbounded memory/disk growth
- Use queue-depth-based autoscaling where justified

Do not continue accepting durable processing work if no credible persistence or recovery path exists.

---

# PART J — FILE VALIDATION AND SECURITY

## 20. SECURITY VALIDATION

Implement:

- File signature validation
- Allowed content types
- File size checks
- Filename normalization
- Safe object key generation
- Malware scanning where supported
- Archive bomb detection
- Nested archive limits
- Parser resource limits
- Media format inspection
- PDF/document validation

Treat the user-provided filename as display metadata, not a filesystem path.

Do not trust file extensions or browser MIME declarations.

---

## 21. QUARANTINE

Use a private quarantine state for unverified objects.

A file must not be downloadable through normal user-facing links until mandatory security and integrity checks pass.

Define:

- Scan pending
- Scan passed
- Scan failed
- Manual review
- Policy rejection
- Timeout
- Cleanup

Never expose a quarantined object through a publicly cached URL.

---

## 22. UNTRUSTED PROCESSING

Run document/image/video processing with:

- CPU limits
- Memory limits
- Timeouts
- Ephemeral disk quotas
- Network restrictions
- Nonprivileged containers
- Controlled process execution
- Safe command arguments
- Output validation

Do not execute embedded macros, scripts, shell commands, or instructions extracted from uploaded files.

---

# PART K — ASYNCHRONOUS PROCESSING

## 23. PROCESSING PIPELINE

Support jobs such as:

- Virus scanning
- Image resizing
- Thumbnail generation
- Video transcoding
- PDF preview generation
- Document metadata extraction
- Compression
- Archive inspection
- Checksum verification
- CSV/data validation
- Custom processing plugin where justified

Implement a complete baseline with at least two meaningful processors, such as image thumbnails and document metadata extraction, plus a video or archive-processing extension.

---

## 24. JOB STATE MACHINE

Suggested states:

- PENDING
- CLAIMED
- RUNNING
- SUCCEEDED
- FAILED_RETRYABLE
- FAILED_TERMINAL
- CANCEL_REQUESTED
- CANCELLED
- MANUAL_REVIEW

Use durable jobs, attempt IDs, leases, and conditional transitions.

---

## 25. WORKER COORDINATION

Implement:

- Atomic job claim
- Lease renewal
- Lease expiration
- Worker heartbeat
- Retry scheduling
- Maximum attempts
- Dead-letter handling
- Stale-worker fencing
- Output idempotency
- Version-aware artifact promotion

### Critical scenario

Worker A starts processing a file.

It pauses.

Its lease expires.

Worker B processes the same file successfully.

Worker A resumes and writes an old result.

Prevent the stale result from replacing the current authoritative artifact.

---

## 26. KAFKA AND OUTBOX

Use Kafka where justified for:

- UploadCompleted
- FileValidated
- ProcessingRequested
- ProcessingSucceeded
- ProcessingFailed
- ArtifactCreated
- FileDeleted

Use Transactional Outbox for reliable integration-event publication following local database commits.

Use durable inbox/idempotent consumers where state-changing effects require deduplication.

Never assume Kafka offsets provide exactly-once object storage operations.

---

# PART L — FILE PROCESSING OUTPUTS

## 27. ARTIFACT MANAGEMENT

Store:

- Original file
- Preview
- Thumbnail
- Transcoded variant
- Metadata
- Validation report
- Processing logs
- Checksum manifest

Link artifacts to a specific file version and processing pipeline version.

Do not expose stale artifacts created for an older file version.

---

## 28. PROCESSING REPRODUCIBILITY

Define:

- Processor version
- Configuration version
- Source file version
- Output identity
- Processing timestamp
- Result status
- Retry history

Support safe reprocessing without creating uncontrolled duplicate artifacts.

---

# PART M — SECURE DOWNLOADS

## 29. DOWNLOAD AUTHORIZATION

Before download:

1. Authenticate or validate an approved share token.
2. Check file ownership and permissions.
3. Verify file state AVAILABLE.
4. Verify requested file version.
5. Apply download policies.
6. Issue a short-lived signed download URL or stream securely where justified.
7. Record appropriate audit events.

Do not expose original private object URLs without access controls.

---

## 30. HTTP RANGE DOWNLOADS

Support resumable downloads using standard HTTP semantics where appropriate:

- `Range`
- `Accept-Ranges`
- `Content-Range`
- `206 Partial Content`
- `416 Range Not Satisfiable`
- `ETag`
- `If-Range`

Define correct behavior for invalid ranges, changed object versions, and partial content.

Do not return 200 with an entire file when the client expects a valid partial response without accounting for the difference.

Prefer letting object storage/CDN handle byte-range transfers when supported.

---

## 31. PARALLEL DOWNLOADS

Implement or evaluate:

- File size discovery
- Bounded byte-range partitioning
- Parallel range requests
- Chunk verification
- Ordered reassembly
- Pause/resume
- Retry missing ranges
- Checksum validation

For browser implementations, define storage/memory limits and use supported streaming or file-system APIs.

Do not assemble a 20 GB download into one JavaScript ArrayBuffer.

Provide a sequential streaming fallback for browsers without necessary capabilities.

---

## 32. DOWNLOAD RESUME

Persist appropriate client-side download metadata when authorized:

- File/version identity
- Total length
- Strong validator where available
- Completed ranges
- Download destination capability
- Expiration
- Verification state

When the underlying object changes, do not append bytes from a new version to an older partial download.

---

# PART N — CDN AND OBJECT STORAGE

## 33. CDN DELIVERY

Evaluate:

- CloudFront
- Another compatible CDN
- Direct object storage for private downloads

Support:

- Cache-control
- Signed URLs/cookies where appropriate
- Byte-range requests
- Origin protection
- Download acceleration
- Cache invalidation
- Private content access
- Regional availability

Do not rely on obscured object keys for confidentiality.

---

## 34. STORAGE LIFECYCLE

Implement:

- Temporary upload cleanup
- Aborted multipart upload cleanup
- Original file retention
- Processed artifact retention
- File deletion
- Storage version cleanup
- Archive lifecycle
- Legal hold where applicable

Reconcile orphaned object-storage state with PostgreSQL records.

---

# PART O — NEXT.JS FRONTEND

## 35. UI/UX OBJECTIVE

Build a polished cloud-drive-style interface with enterprise-grade transfer management.

The UI should feel comparable in interaction quality to modern file management products such as Google Drive, Dropbox, and enterprise upload portals, while using original branding and assets.

Prioritize:

- Clear hierarchy
- Responsive layouts
- Drag-and-drop
- Real-time progress
- Transparent failures
- Accessibility
- Fast navigation
- Keyboard support
- Mobile-friendly interactions
- Safe destructive actions

Do not produce a static dashboard with a decorative upload button.

---

## 36. REQUIRED PAGES

### Dashboard

- Storage usage
- Recent files
- Recent uploads
- Processing jobs
- Transfer activity

### File Manager

- Folders
- Grid/list views
- File metadata
- Search
- Sorting
- Filtering
- Preview
- Rename
- Delete
- Version history

### Upload Manager

- Drag-and-drop zone
- Multi-file selection
- Per-file progress
- Per-chunk progress where useful
- Upload speed
- Estimated time remaining
- Pause
- Resume
- Cancel
- Retry
- Resume after refresh
- Validation errors

### Download Manager

- Download progress
- Transfer speed
- Pause/resume where supported
- Range progress
- Retry
- Verification status

### Processing Dashboard

- Pending jobs
- Running jobs
- Completed jobs
- Failed jobs
- Artifact previews
- Retry/reprocess controls where authorized

### Sharing

- Permissions
- Secure share links
- Expiration
- Revocation

### Admin Portal

- Tenant quotas
- Storage usage
- Upload failures
- Processing backlog
- Quarantine
- Reconciliation
- Audit history
- Operational metrics

---

## 37. UPLOAD COMPONENT DESIGN

Implement a reusable upload component using:

- Browser File/Blob APIs
- Bounded chunk concurrency
- AbortController
- Retry budgets
- Network error handling
- Upload-session recovery
- Progress aggregation
- Browser persistence where appropriate
- Checksums where feasible

Do not assume IndexedDB/localStorage contains authoritative upload state.

Use backend/object-storage reconciliation when resuming.

---

## 38. FRONTEND ACCESSIBILITY

Support:

- Keyboard operation
- Screen readers
- Progressbar ARIA semantics
- File input labels
- Error announcements
- Accessible dialogs
- Focus management
- Reduced motion
- Responsive touch controls

Target WCAG 2.2 AA where practical.

Verify with automated and manual checks.

---

# PART P — REST APIS

## 39. REQUIRED APIS

### Upload Sessions

- `POST /api/v1/uploads`
- `GET /api/v1/uploads/{uploadId}`
- `GET /api/v1/uploads/{uploadId}/parts`
- `POST /api/v1/uploads/{uploadId}/parts/sign`
- `POST /api/v1/uploads/{uploadId}/complete`
- `POST /api/v1/uploads/{uploadId}/abort`
- `POST /api/v1/uploads/{uploadId}/resume`

### Files

- `GET /api/v1/files`
- `GET /api/v1/files/{fileId}`
- `PATCH /api/v1/files/{fileId}`
- `DELETE /api/v1/files/{fileId}`
- `GET /api/v1/files/{fileId}/versions`
- `GET /api/v1/files/{fileId}/download`

### Processing

- `GET /api/v1/files/{fileId}/processing`
- `POST /api/v1/files/{fileId}/reprocess`
- `GET /api/v1/jobs/{jobId}`
- `POST /api/v1/jobs/{jobId}/cancel`

### Sharing

- `POST /api/v1/files/{fileId}/shares`
- `GET /api/v1/files/{fileId}/shares`
- `DELETE /api/v1/shares/{shareId}`

### Administration

- `GET /api/v1/admin/uploads`
- `GET /api/v1/admin/jobs`
- `GET /api/v1/admin/quarantine`
- `GET /api/v1/admin/storage/usage`
- `GET /api/v1/admin/reconciliation`
- `POST /api/v1/admin/reconciliation/run`

### Real-Time Status

- `GET /api/v1/uploads/{uploadId}/events`
- `GET /api/v1/jobs/{jobId}/events`

For every endpoint define:

- Authentication
- Authorization
- Request/response contracts
- Idempotency
- Validation
- Limits
- Timeouts
- Status codes
- Error contracts
- OpenAPI specification

Use RFC 9457 Problem Details where appropriate.

---

# PART Q — RELIABILITY AND FAILURE RECOVERY

## 40. ANALYZE THESE 45 FAILURE SCENARIOS

1. Browser crashes after uploading 60% of a file.
2. Internet connection drops during a part upload.
3. Browser retries a part whose response was lost.
4. A signed URL expires mid-transfer.
5. User changes file selection after creating a session.
6. Two browser tabs resume the same upload.
7. Two clients complete the same upload concurrently.
8. Multipart completion succeeds but the backend times out.
9. Object storage reports missing parts.
10. Client sends an incorrect part manifest.
11. Uploaded object has unexpected size.
12. Client-provided MIME type is incorrect.
13. File checksum does not match expected value.
14. PostgreSQL transaction commits but response is lost.
15. Redis becomes unavailable.
16. Kafka becomes unavailable.
17. Object storage becomes unavailable.
18. Object storage temporarily returns throttling.
19. Upload is cancelled while parts are in flight.
20. Expiration worker races with upload completion.
21. Aborted multipart data remains orphaned.
22. File passes initial validation but fails malware scanning.
23. Scanner becomes unavailable.
24. Processing worker crashes.
25. Two workers process one file version.
26. Old worker result arrives after a newer version is processed.
27. Processing queue becomes overloaded.
28. FFmpeg/document parser consumes excessive memory.
29. Compressed archive expands unexpectedly.
30. Processing job completes but event publication fails.
31. Download starts then network disconnects.
32. Client resumes download using an old ETag.
33. Byte-range request is malformed.
34. CDN caches a file after access revocation.
35. Private download link is leaked.
36. User tries to access another tenant's object.
37. Concurrent uploads exceed a storage quota.
38. Two cleanup workers delete the same temporary object.
39. User deletes a file while processing is running.
40. Object version changes during parallel download.
41. Browser runs out of memory while assembling ranges.
42. One tenant saturates upload bandwidth.
43. Kubernetes pod terminates during job execution.
44. Regional outage occurs during large transfers.
45. Rolling deployment changes upload-session schema.

For each scenario provide:

- Root cause
- Data integrity impact
- Security impact
- User-visible effect
- Detection
- Prevention
- Recovery
- Automated test
- Residual risk

---

# PART R — TESTING STRATEGY

## 41. UNIT TESTS

Cover:

- Chunk boundary calculation
- Part numbering
- Multipart limits
- State transitions
- Upload-session validation
- Idempotency
- Quota calculation
- Checksum validation
- Byte-range parsing
- Download range validation
- File metadata
- Artifact promotion
- Cleanup eligibility

---

## 42. INTEGRATION TESTS

Use Testcontainers or equivalent reproducible infrastructure for:

- PostgreSQL
- Kafka
- Redis where implemented
- S3-compatible object storage

Verify:

- Upload session
- Signed part URLs
- Multipart uploads
- Interrupted/resumed uploads
- Completion validation
- Final object verification
- Processing job creation
- Processing success/failure
- Secure downloads
- Range requests
- Quota enforcement
- Cleanup
- Reconciliation

---

## 43. CONCURRENCY TESTS

Verify:

- Multiple simultaneous upload sessions
- Same upload resumed from two clients
- Concurrent part uploads
- Duplicate completion
- Concurrent cancellation
- Quota contention
- Worker lease races
- Duplicate processing events
- Artifact version fencing
- Download while file changes

Use real PostgreSQL constraints and object-storage behavior where correctness depends on them.

---

## 44. FRONTEND TESTS

Use:

- TypeScript checking
- ESLint
- Component tests
- Playwright
- Accessibility checks

Verify:

- Drag-and-drop
- File selection
- Parallel upload
- Pause/resume
- Cancel
- Retry
- Refresh recovery
- Processing status
- File preview
- Download
- Download resume where supported
- Quota errors
- Unauthorized file access
- Mobile layouts

---

## 45. SECURITY TESTS

Verify:

- Forged upload IDs
- Unauthorized part signing
- Cross-tenant object references
- Object key traversal
- File type spoofing
- Oversized payloads
- Decompression bombs
- Malicious document content
- Signed URL misuse
- Private file leakage
- Download token replay
- Unauthorized share creation
- Expired share links
- Unsafe filename rendering
- SSRF in processing components

Use safe synthetic test files.

---

# PART S — PERFORMANCE ENGINEERING

## 46. CAPACITY PLANNING

Calculate:

1. Concurrent upload sessions
2. Active part requests/sec
3. Average file size
4. Upload bandwidth
5. Download bandwidth
6. Total stored bytes
7. Multipart metadata growth
8. Storage request costs
9. Network egress costs
10. CPU for checksum verification
11. CPU for processing
12. Processing queue backlog
13. Temporary disk usage
14. Worker concurrency
15. Metadata database load
16. Object storage lifecycle costs
17. CDN traffic
18. Multi-region replication costs

Use explicit assumptions and formulas.

Distinguish control-plane throughput from storage data-plane throughput.

---

## 47. BENCHMARK WORKLOADS

Create reproducible tests for:

### File Sizes

- 1 MB
- 100 MB
- 1 GB
- 5 GB
- 20 GB

### Concurrent Uploads

- 10
- 100
- 1,000
- 10,000 as an architectural scale target

### Transfer Profiles

- Fast stable network
- High latency
- Intermittent disconnection
- Packet loss
- Slow bandwidth
- Expired signed URLs

### Processing

- Image thumbnails
- Document metadata
- PDF preview
- Video transcoding where implemented

Measure:

- Upload throughput
- Download throughput
- End-to-end time
- Resume success rate
- Retry amplification
- Part failure rate
- Memory
- CPU
- Network throughput
- Object storage latency
- Job queue depth
- Processing duration
- Storage costs
- Invalid/corrupted file rate

Never fabricate benchmark results.

---

# PART T — OBSERVABILITY

## 48. METRICS

Use Micrometer, OpenTelemetry, Prometheus, and Grafana.

Expose:

- Upload sessions started
- Upload sessions completed
- Upload failures
- Active transfers
- Bytes uploaded
- Bytes downloaded
- Multipart completion latency
- Part retries
- Resume operations
- Checksum failures
- Validation failures
- Malware scan failures
- Processing backlog
- Processing duration
- Processing retries
- Download failures
- Range request failures
- Signed URL failures
- Quota rejections
- Cleanup backlog
- Orphaned multipart uploads
- Reconciliation mismatches
- Object storage latency
- JVM CPU/heap/GC

Avoid raw file names, file IDs, user IDs, and upload IDs as unrestricted metric labels.

---

# PART U — SIX AWS WELL-ARCHITECTED PILLARS

## 49. OPERATIONAL EXCELLENCE

Implement:

- CI/CD
- Upload/processing dashboards
- Runbooks
- Audit events
- Safe deployment
- Feature flags
- Operational alerts
- Controlled rollback

## 50. SECURITY

Implement:

- OAuth2/OIDC
- Spring Security
- Object authorization
- Signed URLs
- TLS
- Encryption
- Malware scanning
- Quarantine
- Tenant isolation
- Secret management
- Audit logging
- Data retention

## 51. RELIABILITY

Implement:

- Durable upload sessions
- Multipart recovery
- Idempotency
- Processing worker recovery
- Outbox/Inbox
- Retry/DLQ
- Backups
- Object versioning
- Reconciliation
- Multi-AZ design

## 52. PERFORMANCE EFFICIENCY

Implement:

- Direct-to-S3 transfer
- Parallel chunking
- Adaptive concurrency
- Streaming
- CDN
- Async workers
- Connection reuse
- Bounded processing
- Load testing

## 53. COST OPTIMIZATION

Evaluate:

- Object storage pricing
- Multipart request cost
- Temporary part cleanup
- CDN egress
- Processing CPU
- Archive retention
- Compression
- Autoscaling
- Managed versus self-hosted infrastructure

## 54. SUSTAINABILITY

Consider:

- Avoiding unnecessary reuploads
- Resume instead of restart
- Deduplication where justified
- Efficient checksum processing
- Optimized artifacts
- Reduced network transfer
- Storage lifecycle
- Scale-to-demand processing

Create an AWS Well-Architected review matrix with design decisions, trade-offs, test evidence, and residual risks.

---

# PART V — PROJECT STRUCTURE

## 55. RECOMMENDED MONOREPO

```text
large-file-transfer-platform/
├── README.md
├── docs/
│   ├── requirements/
│   ├── architecture/
│   ├── upload-protocol/
│   ├── download-protocol/
│   ├── object-storage/
│   ├── processing/
│   ├── integrity/
│   ├── security/
│   ├── capacity-planning/
│   ├── aws-well-architected/
│   ├── runbooks/
│   └── decisions/
├── backend/
│   ├── pom.xml
│   ├── file-domain/
│   ├── upload-management/
│   ├── download-management/
│   ├── storage-adapters/
│   ├── processing-orchestrator/
│   ├── processing-workers/
│   ├── quota-management/
│   ├── reconciliation/
│   └── integration-tests/
├── frontend/
│   ├── app/
│   ├── components/
│   ├── features/
│   ├── hooks/
│   ├── lib/
│   ├── styles/
│   └── tests/
├── infrastructure/
│   ├── postgres/
│   ├── redis/
│   ├── kafka/
│   ├── object-storage/
│   ├── cdn/
│   ├── monitoring/
│   ├── docker/
│   ├── helm/
│   └── terraform/
├── test-data/
├── benchmarks/
├── end-to-end-tests/
└── .github/workflows/
```

Adapt module/deployment boundaries to real scaling and ownership requirements.

Avoid empty modules, speculative abstractions, and unnecessary microservices.

---

# PART W — IMPLEMENTATION WORKFLOW

## 56. EXECUTE IN PHASES

### Phase 1 — Requirements and Architecture

Define file limits, storage model, transfer protocols, consistency, integrity, security, SLOs, and scale assumptions.

### Phase 2 — Spring Boot 4.x Foundation

Create Java 21 backend, authentication, PostgreSQL migrations, and local infrastructure.

### Phase 3 — Next.js Design System

Create reusable file manager, upload list, progress components, responsive layouts, and accessible interaction patterns.

### Phase 4 — Object Storage Integration

Implement storage provider abstraction, private buckets, pre-signed URLs, and safe object key management.

### Phase 5 — Multipart Upload

Implement session creation, part signing, manifest persistence, and completion.

### Phase 6 — Resumability

Implement pause/resume, refresh recovery, missing-part reconciliation, and session expiration.

### Phase 7 — Data Integrity

Implement checksums, final-object validation, ownership verification, and content-type validation.

### Phase 8 — Async Processing

Implement durable jobs, Kafka/outbox where justified, worker leases, retry, and artifact versioning.

### Phase 9 — Security and Quarantine

Implement scanning, file limits, safe parsing, quarantine, and authorized promotion.

### Phase 10 — Secure Downloads

Implement signed downloads, version validation, byte ranges, and streaming.

### Phase 11 — Resumable Downloads

Implement bounded parallel range downloading, pause/resume, and verified reassembly where supported.

### Phase 12 — File Management

Implement metadata, folders, file versions, search, sharing, and retention.

### Phase 13 — Quotas and Fairness

Implement per-user/tenant quotas, storage reservations, admission control, and overload protection.

### Phase 14 — Reconciliation

Implement cleanup of abandoned sessions, missing objects, stale jobs, leaked quota reservations, and orphaned parts.

### Phase 15 — Functional Frontend

Complete upload manager, download manager, file explorer, processing dashboard, and admin controls.

### Phase 16 — Security Hardening

Implement threat mitigations, tenant isolation, signed URL restrictions, and adversarial tests.

### Phase 17 — Observability

Implement metrics, traces, structured logs, alerts, and runbooks.

### Phase 18 — Deployment

Implement Docker Compose, Kubernetes/Helm, CI/CD, and AWS reference infrastructure.

### Phase 19 — Verification

Run unit, integration, concurrency, security, recovery, and Playwright tests.

### Phase 20 — Performance

Benchmark transfers, processing, scaling, memory, retry amplification, and bandwidth/cost.

### Phase 21 — Final Architecture Review

Evaluate correctness, availability, integrity, privacy, durability, operations, and the six AWS Well-Architected pillars.

For every phase:

- Implement actual source files.
- Keep the project buildable.
- Run relevant tests.
- Fix discovered defects.
- Preserve unrelated work.
- Document significant design decisions.
- Report actual results.
- Identify incomplete or unverified capabilities.

---

# PART X — MANDATORY ARCHITECTURE QUESTIONS

## 57. ANSWER THESE 40 QUESTIONS

1. How would you design a system supporting 100,000 concurrent large-file uploads?
2. Why should large files bypass Spring Boot application servers?
3. How do S3 pre-signed URLs work?
4. How does multipart upload work internally?
5. How do you choose chunk size?
6. How many parallel chunks should a client upload?
7. How do you resume an interrupted multipart upload?
8. How does the tus protocol differ from S3 multipart?
9. How do you prevent duplicate upload completion?
10. Why is a multipart ETag not necessarily an MD5 checksum?
11. How do you verify file integrity end to end?
12. How do you prevent malicious file uploads?
13. Why is quarantine necessary?
14. How do you validate file content types?
15. How do you avoid buffering huge files in JVM memory?
16. How do you process uploaded files asynchronously?
17. How do worker leases and fencing prevent stale processing results?
18. How do you make object storage processing idempotent?
19. What is Transactional Outbox?
20. How do you reconcile PostgreSQL and object storage?
21. How do you clean up abandoned multipart uploads?
22. How do you implement tenant storage quotas correctly?
23. How do you handle Redis/object storage outages?
24. What are HTTP Range requests?
25. How does `206 Partial Content` work?
26. What is the role of `If-Range`?
27. How do you resume a large download safely?
28. How do you merge parallel download ranges?
29. How do you prevent corrupt range reassembly?
30. How do signed URLs protect private files?
31. How do CDN caching and authorization interact?
32. How do you prevent a leaked signed URL from allowing unrestricted access?
33. How do you control memory usage in browser upload/download logic?
34. How do you handle upload retries and backoff?
35. How do you avoid retry storms?
36. How do you isolate CPU-heavy media/document processing?
37. How do you scale processing jobs independently from uploads?
38. How do you estimate object storage and CDN costs?
39. How would you benchmark upload/download throughput under network failures?
40. What evidence is required before enterprise production deployment?

For every answer explain architecture, complexity, concurrency, consistency, failure recovery, security, performance, and trade-offs.

---

# DEFINITION OF DONE

## 58. ACCEPTANCE CRITERIA

The implementation is complete only when:

- Java 21 Spring Boot 4.x builds.
- Next.js TypeScript builds.
- PostgreSQL migrations execute.
- Authentication and authorization work.
- Multipart upload session creation works.
- Signed part URLs work.
- Chunked parallel uploads work.
- Pause/resume/cancel works.
- Browser refresh recovery works.
- Duplicate completion is idempotent.
- Final object verification works.
- Checksums follow documented semantics.
- Invalid or unsafe files are quarantined/rejected.
- Async processing jobs run.
- Worker crash recovery works.
- Stale results cannot replace newer artifacts.
- File metadata and versions work.
- Secure download links work.
- HTTP Range support works.
- Resumable download is implemented under supported browser capabilities.
- Download integrity is verified.
- Quotas are enforced.
- Abandoned uploads are cleaned up.
- Reconciliation detects orphaned or inconsistent state.
- Next.js upload manager is functional.
- Next.js download manager is functional.
- Processing and admin dashboards use real APIs.
- Docker Compose demonstrates a complete end-to-end flow.
- Kubernetes configuration is provided.
- Automated tests cover major failure and concurrency cases.
- Metrics and tracing function.
- Benchmarks are reproducible.
- Performance targets are measured or explicitly unverified.
- All six AWS Well-Architected pillars are reviewed.
- Remaining risks are documented.

Do not declare production readiness based solely on a successful small-file upload.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, enterprise-grade High-Concurrency Large File Upload, Download, and Processing Platform using Java 21, Spring Boot 4.x, and Next.js**.

Prioritize:

1. **Transfer correctness:** Complete and verifiable uploaded/downloaded bytes.
2. **Resumability:** Recovery from network, browser, and process failures.
3. **Memory efficiency:** Streaming, direct object storage, bounded buffers, and chunk concurrency.
4. **Integrity:** Checksums, manifests, object verification, and artifact versioning.
5. **Security:** Authorization, secure signed URLs, quarantine, scanning, and tenant isolation.
6. **Reliable async processing:** Durable jobs, leases, retries, idempotency, and reconciliation.
7. **Scalability:** Independent control/data plane scaling, admission control, and workload isolation.
8. **Frontend quality:** Polished and accessible transfer management with reliable progress and recovery.
9. **Operational resilience:** Clear failures, bounded retries, alerts, and recovery runbooks.
10. **Maintainability:** Pragmatic DDD, SOLID, and Clean/Hexagonal Architecture.
11. **Performance:** Measured throughput, memory, latency, bandwidth, and cost.
12. **Cloud quality:** All six AWS Well-Architected pillars.
13. **Reproducibility:** Complete source, tests, Docker Compose, Kubernetes, seed data, and benchmarks.

Begin with a concise architecture assessment.

Select the upload protocol, chunk sizing, concurrency policy, state model, integrity contract, processing pipeline, and download strategy.

Then implement the platform phase by phase.

Use available development tools to create source code, compile, execute tests, simulate interrupted transfers and worker crashes, verify file integrity, run the frontend, benchmark supported workloads, and fix defects.

At completion report:

- Implemented backend features
- Implemented Next.js pages
- Transfer/resume guarantees
- Integrity verification model
- Processing pipeline
- Architecture decisions
- Project structure
- Exact build/run commands
- Tests actually executed
- Benchmark results actually measured
- AWS Well-Architected assessment
- Known limitations
- Remaining production risks

**DO NOT STOP AFTER HLD OR LLD. DELIVER THE COMPLETE END-TO-END LARGE FILE UPLOAD, RESUMABLE DOWNLOAD, AND ASYNCHRONOUS PROCESSING PLATFORM WITH JAVA 21, SPRING BOOT 4.x, NEXT.JS, S3 MULTIPART UPLOADS, CHECKSUM VALIDATION, DURABLE WORKERS, SECURE RANGE DOWNLOADS, AUTOMATED VERIFICATION, AND DEPLOYMENT INFRASTRUCTURE.**
````