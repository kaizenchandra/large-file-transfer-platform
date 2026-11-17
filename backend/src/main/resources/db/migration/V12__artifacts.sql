CREATE TABLE processed_artifacts (
 job uuid NOT NULL REFERENCES processing_jobs ON DELETE CASCADE,
 variant text NOT NULL, source uuid NOT NULL REFERENCES file_versions ON DELETE CASCADE,
 object_key text NOT NULL UNIQUE, size bigint NOT NULL, sha256 text NOT NULL,
 processor text NOT NULL, PRIMARY KEY(job,variant)
);
