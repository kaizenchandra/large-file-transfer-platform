-- Forward migration: do not change V1 checksums on existing installations.
ALTER TABLE file_versions DROP CONSTRAINT file_versions_size_check;
ALTER TABLE file_versions
    ADD CONSTRAINT file_versions_size_check CHECK (size >=0);
ALTER TABLE upload_sessions DROP CONSTRAINT upload_sessions_size_check;
ALTER TABLE upload_sessions
    ADD CONSTRAINT upload_sessions_size_check CHECK (size >=0);
CREATE INDEX version_history ON file_versions (node, created DESC, id DESC);
CREATE INDEX pending_uploads ON upload_sessions (expires) WHERE state IN ('INITIATED','UPLOADING','COMPLETING');
CREATE INDEX node_updated ON file_nodes (ns, updated, id) WHERE NOT deleted;
