alter table user_account drop constraint user_account_role_check;
alter table user_account add constraint user_account_role_check check (role in ('RECRUITER', 'ADMIN'));

create table vacancy (
    id uuid primary key,
    title text not null,
    title_normalized text not null,
    description text not null,
    reception_start_utc timestamptz not null,
    reception_end_utc timestamptz not null,
    status varchar(20) not null check (status in ('ACTIVE', 'ARCHIVED')),
    version bigint not null default 0 check (version >= 0),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    check (reception_start_utc <= reception_end_utc)
);

create index idx_vacancy_active_title_normalized on vacancy(title_normalized) where status = 'ACTIVE';
create index idx_vacancy_status on vacancy(status);

create table vacancy_requirement (
    id uuid primary key,
    vacancy_id uuid not null references vacancy(id),
    description text not null,
    weight smallint not null check (weight between 1 and 5),
    mandatory boolean not null,
    position integer not null check (position >= 0),
    unique (vacancy_id, position)
);

create index idx_vacancy_requirement_vacancy_position on vacancy_requirement(vacancy_id, position);
