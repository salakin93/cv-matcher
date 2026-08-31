CREATE TABLE microsoft_oauth_connection (
    id UUID PRIMARY KEY,
    refresh_token_ciphertext BYTEA NOT NULL,
    refresh_token_nonce BYTEA NOT NULL,
    encryption_key_version VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX ux_microsoft_oauth_connection_active
    ON microsoft_oauth_connection ((1))
    WHERE active;

CREATE TABLE microsoft_oauth_authorization_attempt (
    id UUID PRIMARY KEY,
    state_hash CHAR(64) NOT NULL UNIQUE,
    code_verifier_ciphertext BYTEA NOT NULL,
    code_verifier_nonce BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE matching_job_message (
    id UUID PRIMARY KEY,
    matching_job_id UUID NOT NULL REFERENCES matching_job(id) ON DELETE CASCADE,
    graph_message_id VARCHAR(512) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_matching_job_message UNIQUE (matching_job_id, graph_message_id)
);

CREATE TABLE ingested_document (
    id UUID PRIMARY KEY,
    sha256 CHAR(64) NOT NULL UNIQUE,
    original_artifact_path VARCHAR(512) NOT NULL,
    original_nonce BYTEA NOT NULL,
    text_artifact_path VARCHAR(512) NULL,
    text_nonce BYTEA NULL,
    encryption_key_version VARCHAR(32) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    extraction_status VARCHAR(64) NOT NULL,
    ignored_reason VARCHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL,
    retained_until TIMESTAMPTZ NOT NULL
);

CREATE TABLE matching_job_document_reference (
    id UUID PRIMARY KEY,
    matching_job_id UUID NOT NULL REFERENCES matching_job(id) ON DELETE CASCADE,
    matching_job_message_id UUID NOT NULL REFERENCES matching_job_message(id) ON DELETE CASCADE,
    graph_attachment_id VARCHAR(512) NOT NULL,
    ingested_document_id UUID NULL REFERENCES ingested_document(id),
    status VARCHAR(64) NOT NULL,
    ignored_reason VARCHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_matching_job_attachment UNIQUE (matching_job_message_id, graph_attachment_id)
);

CREATE INDEX ix_ingested_document_retained_until ON ingested_document(retained_until);
CREATE INDEX ix_matching_job_document_reference_job ON matching_job_document_reference(matching_job_id);

ALTER TABLE matching_job
    ADD COLUMN processed_messages INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN accepted_documents INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN ignored_documents INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN duplicate_documents INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN safe_warning VARCHAR(64) NULL,
    ADD COLUMN safe_error_category VARCHAR(64) NULL;
