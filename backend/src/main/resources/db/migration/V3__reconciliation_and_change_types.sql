ALTER TABLE upload_sessions
    ADD COLUMN retry_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE upload_sessions
    ADD COLUMN failures integer NOT NULL DEFAULT 0 CHECK (failures >= 0);
ALTER TABLE change_log
    ADD COLUMN action text NOT NULL DEFAULT 'UPDATE';
CREATE INDEX completing_retry ON upload_sessions (retry_at) WHERE state='COMPLETING';
