ALTER TABLE garbage_objects ADD COLUMN processing_job uuid REFERENCES processing_jobs ON DELETE SET NULL;
CREATE INDEX garbage_processing_job ON garbage_objects(processing_job) WHERE processing_job IS NOT NULL;
