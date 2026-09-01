ALTER TABLE matching_job
    ADD COLUMN ingestion_claimed_at TIMESTAMPTZ NULL,
    ADD COLUMN ingestion_checkpoint VARCHAR(64) NULL;

CREATE INDEX ix_matching_job_recoverable_ingestion
    ON matching_job (status, ingestion_claimed_at)
    WHERE status IN ('QUEUED', 'INGESTING_EMAILS');
