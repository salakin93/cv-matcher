create table matching_job_discovered_message (
    id uuid primary key,
    matching_job_id uuid not null references matching_job(id) on delete restrict,
    graph_message_id varchar(1024) not null,
    received_at timestamptz not null,
    has_attachments boolean not null,
    created_at timestamptz not null,
    constraint uq_matching_job_discovered_message unique (matching_job_id, graph_message_id)
);

create index idx_matching_job_discovered_message_job_received
    on matching_job_discovered_message(matching_job_id, received_at asc, id asc);

alter table matching_job
    add column discovered_message_count integer not null default 0 check (discovered_message_count >= 0),
    add column discovery_completed_at timestamptz;
