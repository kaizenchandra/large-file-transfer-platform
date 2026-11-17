ALTER TABLE upload_sessions ADD COLUMN completion_fence bigint NOT NULL DEFAULT 0;
ALTER TABLE upload_sessions ADD COLUMN completion_lease timestamptz;
ALTER TABLE upload_sessions ADD COLUMN completion_attempts int NOT NULL DEFAULT 0;
