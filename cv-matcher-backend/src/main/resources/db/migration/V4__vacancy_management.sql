create table vacancy (
    id uuid primary key,
    title varchar(160) not null,
    description varchar(10000) not null,
    received_from_utc timestamptz not null,
    received_to_utc_exclusive timestamptz not null,
    status varchar(20) not null check (status in ('ACTIVE', 'ARCHIVED')),
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint chk_vacancy_received_range check (received_from_utc < received_to_utc_exclusive)
);

create index idx_vacancy_status_updated_id on vacancy(status, updated_at desc, id asc);

create table vacancy_requirement (
    id uuid primary key,
    vacancy_id uuid not null references vacancy(id) on delete restrict,
    description varchar(1000) not null,
    weight smallint not null check (weight between 1 and 5),
    mandatory boolean not null,
    position integer not null check (position >= 0),
    constraint uq_vacancy_requirement_position unique (vacancy_id, position)
);

create index idx_vacancy_requirement_vacancy_position on vacancy_requirement(vacancy_id, position);
