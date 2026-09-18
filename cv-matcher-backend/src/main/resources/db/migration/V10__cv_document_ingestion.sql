create table candidate_document (
    id uuid primary key,
    graph_message_id_hash char(64) not null,
    graph_attachment_id_hash char(64) not null,
    content_sha256 char(64),
    storage_key varchar(160),
    format varchar(8),
    size_bytes bigint,
    encryption_key_version integer not null,
    status varchar(16) not null check (status in ('AVAILABLE', 'IGNORED', 'QUARANTINED')),
    ignored_reason_code varchar(80),
    received_at timestamptz not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_candidate_document_graph_attachment unique (graph_message_id_hash, graph_attachment_id_hash),
    constraint uq_candidate_document_storage_key unique (storage_key),
    constraint chk_candidate_document_available check (
        status <> 'AVAILABLE' or (storage_key is not null and content_sha256 is not null and format in ('PDF', 'DOCX') and size_bytes > 0)
    ),
    constraint chk_candidate_document_ignored check (status <> 'IGNORED' or storage_key is null),
    constraint chk_candidate_document_size check (size_bytes is null or size_bytes > 0)
);

create index idx_candidate_document_content_sha256 on candidate_document(content_sha256);

create table matching_job_document (
    id uuid primary key,
    matching_job_id uuid not null references matching_job(id) on delete restrict,
    candidate_document_id uuid not null references candidate_document(id) on delete restrict,
    position integer not null check (position >= 0),
    disposition varchar(16) not null check (disposition in ('ACCEPTED', 'IGNORED', 'QUARANTINED')),
    reason_code varchar(80),
    created_at timestamptz not null,
    constraint uq_matching_job_document_document unique (matching_job_id, candidate_document_id),
    constraint uq_matching_job_document_position unique (matching_job_id, position)
);

alter table matching_job
    add column accepted_document_count integer not null default 0 check (accepted_document_count >= 0),
    add column ignored_document_count integer not null default 0 check (ignored_document_count >= 0),
    add column quarantined_document_count integer not null default 0 check (quarantined_document_count >= 0),
    add column ingestion_completed_at timestamptz;
