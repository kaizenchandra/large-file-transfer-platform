# Management and media operations

## Identity and authorization

Production administration requires the validated JWT issuer/audience, a tenant claim and the trusted `roles` list containing `tenant_admin`. A user cannot nominate a tenant in an administration URL. Local-only Basic admin is enabled with `drive.local=true`; ordinary development accounts remain non-admin. All file IDs must belong to the authorized namespace before owner privileges apply. Never repair access failures by copying node IDs or relaxing tenant predicates.

## Admission and quota

Defaults are 16 active uploads and 120 newly admitted sessions per minute per namespace. Configure `drive.admission.max-active-uploads` and `drive.admission.sessions-per-minute` with Spring properties. Existing matching idempotency receipts are returned before charging another admission. A 429 carries Retry-After: 60. Admission counters live in PostgreSQL; Redis failure cannot erase the hard limit.

Admin `GET /api/v1/admin/storage/usage` returns the expected quota value. `PATCH /api/v1/admin/storage/{namespace}/quota` accepts `{bytes,expected}`. A stale expected quota returns 412; a quota below committed+reserved+quarantined bytes returns 409. Do not decrement accounting manually while an upload or forensic object still exists. Namespace quotas do not yet provide a tenant aggregate storage cap.

## Reconciliation

`POST /api/v1/admin/reconciliation/run` returns 202 with a durable request. Concurrent active requests for a tenant resolve to the same row. Inspect `GET /api/v1/admin/reconciliation/requests`; the scheduled worker claims with an expiring lease and fence, retries at most three times and records terminal failure. `GET /api/v1/admin/reconciliation` summarizes discrepancies. Review failed requests and underlying provider health before submitting another run. This is tenant-scoped reconciliation, not a database repair command.

## Processing and artifacts

`POST /api/v1/files/{file}/reprocess` checks owner and current source scan authorization. Supply Idempotency-Key when retrying. Active jobs return 409. Terminal jobs are reset with a new fence, while the unique job/source identity and attempt history remain. `GET /api/v1/jobs/{job}/attempts` exposes the latest 100 attempts. Hard-crashed/expired attempts may still retain RUNNING history until further history reconciliation is implemented; current job/fence is authoritative.

Artifact APIs require a successful current job and a matching artifact fence. A stale row is insufficient to issue a credential. Downloads and artifact credentials produce audit records. A signed credential can remain usable until its short expiration, even after permission revocation.

Configure `MEDIA_SANDBOX_URL` and `MEDIA_SANDBOX_TOKEN` only to the trusted loopback broker described in sandbox/README.md. No Docker socket is mounted into the API or parser containers. Full short-video conversion is limited to 64 MiB input, 120 seconds, 45 seconds per encoder invocation and 8 MiB output. Policy or parser failures do not approve an artifact; they currently use the bounded generic job retry policy. The original version has already passed mandatory upload integrity/scanning and is independent of supplemental artifact success.

## Browser verification

The recovery lab must be explicitly enabled on a private local frontend with `HARBOR_RECOVERY_LAB=1`. Build with the intended API_ORIGIN; Next.js rewrites are generated during build. Start the production frontend on an origin allowed by storage CORS, then run from frontend/:

```sh
BROWSER_ORIGIN=http://localhost:3011 DRIVE_API=http://localhost:28084/api/v1 \
STORAGE_ORIGIN=http://localhost:39000 npm run test:restart
BROWSER_ORIGIN=http://localhost:3011 DRIVE_API=http://localhost:28084/api/v1 \
node tests/workspace.cjs
```

The checked-in tests use the isolated benchmark development password by default; restart tests can override DRIVE_AUTH. Never point these fixtures at production identities or customer storage. Recovery uses real Chromium process closure/relaunch and persisted OPFS/IndexedDB handles; it does not simulate OS power loss or native picker permission prompts.
