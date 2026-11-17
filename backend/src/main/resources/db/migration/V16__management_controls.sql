CREATE TABLE api_rate_windows(ns uuid NOT NULL REFERENCES namespaces,window_start timestamptz NOT NULL,requests integer NOT NULL CHECK(requests>0),PRIMARY KEY(ns,window_start));
CREATE INDEX audit_tenant_page ON audit_records(ns,id DESC);
CREATE INDEX uploads_active_namespace ON upload_sessions(ns,state) WHERE state IN ('INITIATED','UPLOADING','COMPLETING');
CREATE TABLE reconciliation_requests(id uuid PRIMARY KEY,tenant text NOT NULL,actor text NOT NULL,state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),fence bigint NOT NULL DEFAULT 0,attempts int NOT NULL DEFAULT 0,lease_until timestamptz,created timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX reconciliation_request_pending ON reconciliation_requests(tenant) WHERE state IN ('PENDING','RUNNING');
