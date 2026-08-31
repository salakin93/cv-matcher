CREATE TABLE matching_job (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(10000) NOT NULL,
    from_timestamp TIMESTAMPTZ NOT NULL,
    to_timestamp TIMESTAMPTZ NOT NULL,
    status VARCHAR(64) NOT NULL,
    job_mode VARCHAR(32) NOT NULL,
    retry_of_job_id UUID NULL REFERENCES matching_job(id),
    correlation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_matching_job_range CHECK (from_timestamp <= to_timestamp)
);

CREATE TABLE matching_job_requirement (
    id UUID PRIMARY KEY,
    matching_job_id UUID NOT NULL REFERENCES matching_job(id) ON DELETE CASCADE,
    description VARCHAR(2000) NOT NULL,
    weight INTEGER NOT NULL CHECK (weight BETWEEN 1 AND 5),
    mandatory BOOLEAN NOT NULL,
    display_order INTEGER NOT NULL,
    CONSTRAINT uq_matching_job_requirement_order UNIQUE (matching_job_id, display_order)
);

CREATE TABLE matching_job_event (
    id UUID PRIMARY KEY,
    matching_job_id UUID NOT NULL REFERENCES matching_job(id) ON DELETE CASCADE,
    previous_status VARCHAR(64),
    new_status VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    safe_details VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX ux_matching_job_one_active
    ON matching_job ((1))
    WHERE status IN (
        'QUEUED', 'INGESTING_EMAILS', 'SCANNING_DOCUMENTS',
        'EXTRACTING_TEXT', 'TEXT_EXTRACTION_COMPLETE', 'ANALYZING_CANDIDATES'
    );
