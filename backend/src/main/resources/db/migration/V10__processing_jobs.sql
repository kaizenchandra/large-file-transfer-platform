CREATE TABLE processing_jobs (
 id uuid PRIMARY KEY, ns uuid NOT NULL REFERENCES namespaces, node uuid NOT NULL REFERENCES file_nodes ON DELETE CASCADE,
 source uuid NOT NULL REFERENCES file_versions ON DELETE CASCADE, state text NOT NULL DEFAULT 'PENDING',
 fence bigint NOT NULL DEFAULT 0, attempts int NOT NULL DEFAULT 0,
 lease_until timestamptz, available_at timestamptz NOT NULL DEFAULT now(),
 report jsonb, error text, created timestamptz NOT NULL DEFAULT now(),
 UNIQUE(source), CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','FAILED_RETRYABLE','FAILED_TERMINAL','CANCELLED'))
);
CREATE INDEX processing_claim ON processing_jobs(available_at) WHERE state IN ('PENDING','RUNNING','FAILED_RETRYABLE');
