CREATE TABLE processing_attempts(job uuid NOT NULL REFERENCES processing_jobs ON DELETE CASCADE,fence bigint NOT NULL,started timestamptz NOT NULL DEFAULT now(),finished timestamptz,outcome text NOT NULL DEFAULT 'RUNNING',PRIMARY KEY(job,fence));
ALTER TABLE processed_artifacts ADD COLUMN fence bigint NOT NULL DEFAULT 0;
UPDATE processed_artifacts a SET fence=j.fence FROM processing_jobs j WHERE a.job=j.id;
