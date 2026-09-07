create table matching_job (
    id uuid primary key,
    vacancy_id uuid not null,
    requested_by_user_id uuid not null references user_account(id) on delete restrict,
    vacancy_version bigint not null,
    vacancy_title varchar(160) not null,
    received_from_utc timestamptz not null,
    received_to_utc_exclusive timestamptz not null,
    status varchar(40) not null check (status in ('QUEUED', 'DISCOVERING', 'INGESTING_DOCUMENTS', 'ANALYZING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'REAUTHORIZATION_REQUIRED', 'CANCELLED')),
    attempt integer not null check (attempt >= 1),
    retry_of_job_id uuid references matching_job(id) on delete restrict,
    claimed_by varchar(120),
    lease_until timestamptz,
    failure_code varchar(80),
    created_at timestamptz not null,
    started_at timestamptz,
    finished_at timestamptz,
    updated_at timestamptz not null,
    constraint chk_matching_job_range check (received_from_utc < received_to_utc_exclusive)
);

create unique index uq_matching_job_active_vacancy on matching_job(vacancy_id)
    where status in ('QUEUED', 'DISCOVERING', 'INGESTING_DOCUMENTS', 'ANALYZING');
create index idx_matching_job_vacancy_created on matching_job(vacancy_id, created_at desc, id asc);
create index idx_matching_job_status_lease_created on matching_job(status, lease_until, created_at);

create table matching_job_requirement (
    id uuid primary key,
    matching_job_id uuid not null references matching_job(id) on delete restrict,
    description varchar(1000) not null,
    weight smallint not null check (weight between 1 and 5),
    mandatory boolean not null,
    position integer not null check (position >= 0),
    constraint uq_matching_job_requirement_position unique (matching_job_id, position)
);

create table matching_job_event (
    id uuid primary key,
    matching_job_id uuid not null references matching_job(id) on delete restrict,
    actor_user_id uuid references user_account(id) on delete restrict,
    action varchar(80) not null,
    from_status varchar(40),
    to_status varchar(40),
    correlation_id uuid,
    created_at timestamptz not null
);

create index idx_matching_job_event_job_created on matching_job_event(matching_job_id, created_at, id);
