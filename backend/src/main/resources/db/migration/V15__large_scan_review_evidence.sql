ALTER TABLE upload_sessions DROP CONSTRAINT upload_sessions_scan_status_check;
ALTER TABLE upload_sessions ADD CONSTRAINT upload_sessions_scan_status_check CHECK(scan_status IN ('PENDING','CLEAN','BLOCKED','BYPASSED','REVIEW_REQUIRED'));
ALTER TABLE blob_scans DROP CONSTRAINT blob_scans_verdict_check;
ALTER TABLE blob_scans ADD CONSTRAINT blob_scans_verdict_check CHECK(verdict IN ('CLEAN','BLOCKED','BYPASSED','REVIEW_REQUIRED'));
ALTER TABLE blob_scans ADD COLUMN covered_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE blob_scans ADD COLUMN segments integer NOT NULL DEFAULT 0;
ALTER TABLE blob_scans ADD COLUMN scope text NOT NULL DEFAULT 'legacy';
