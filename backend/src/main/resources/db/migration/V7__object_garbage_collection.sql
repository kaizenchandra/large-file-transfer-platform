CREATE INDEX version_object_references ON file_versions (object_key);
CREATE TABLE garbage_objects
(
    object_key text PRIMARY KEY,
    ns         uuid        NOT NULL REFERENCES namespaces,
    marked_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    failures   integer     NOT NULL DEFAULT 0,
    retry_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX garbage_pending ON garbage_objects (retry_at, marked_at) WHERE deleted_at IS NULL;
