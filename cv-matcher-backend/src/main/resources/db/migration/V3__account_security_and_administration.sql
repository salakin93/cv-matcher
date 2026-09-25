alter table user_account
    add column password_change_required boolean not null default false,
    add column version bigint not null default 0 check (version >= 0);

create table identity_bootstrap (
    id boolean primary key default true check (id),
    completed_at timestamptz not null
);

create table password_reset (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    token_hash varchar(128) not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null
);

create index idx_password_reset_user_active on password_reset(user_id) where consumed_at is null;

create table email_change (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    target_email varchar(320) not null,
    target_email_normalized varchar(320) not null,
    token_hash varchar(128) not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null
);

create unique index uq_email_change_target_active on email_change(target_email_normalized) where consumed_at is null;
create index idx_email_change_user_active on email_change(user_id) where consumed_at is null;

create table account_security_request (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    purpose varchar(30) not null check (purpose in ('PASSWORD_RESET', 'EMAIL_CHANGE')),
    requested_at timestamptz not null
);

create index idx_account_security_request_user_purpose_requested
    on account_security_request(user_id, purpose, requested_at);

alter table outbox_message drop constraint outbox_message_purpose_check;
alter table outbox_message add constraint outbox_message_purpose_check check (purpose in (
    'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'EMAIL_CHANGE', 'ACCOUNT_ROLE_CHANGED', 'ACCOUNT_ACTIVATED', 'ACCOUNT_DEACTIVATED'
));
