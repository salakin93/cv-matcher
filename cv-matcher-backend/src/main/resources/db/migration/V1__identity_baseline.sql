create table user_account (
    id uuid primary key,
    full_name varchar(200) not null,
    email varchar(320) not null,
    email_normalized varchar(320) not null unique,
    password_hash varchar(500) not null,
    role varchar(20) not null check (role = 'RECRUITER'),
    status varchar(20) not null check (status in ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED')),
    email_verified_at timestamptz,
    failed_login_attempts integer not null default 0,
    locked_until timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table user_session (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    refresh_token_hash varchar(128) not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null
);

create index idx_user_session_user on user_session(user_id);

create table account_action_token (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    token_hash varchar(128) not null unique,
    purpose varchar(30) not null check (purpose = 'EMAIL_VERIFICATION'),
    target_email varchar(320),
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null
);

create index idx_action_token_user_purpose on account_action_token(user_id, purpose) where consumed_at is null;

create table verification_resend_attempt (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    requested_at timestamptz not null
);

create index idx_verification_resend_attempt_user_requested
    on verification_resend_attempt(user_id, requested_at);

create table outbox_message (
    id uuid primary key,
    recipient varchar(320) not null,
    purpose varchar(40) not null check (purpose = 'EMAIL_VERIFICATION'),
    payload_ciphertext bytea not null,
    encryption_key_version integer not null check (encryption_key_version > 0),
    created_at timestamptz not null,
    delivered_at timestamptz,
    attempts integer not null default 0 check (attempts >= 0)
);

create index idx_outbox_message_pending on outbox_message(created_at) where delivered_at is null;

create table audit_event (
    id uuid primary key,
    actor_user_id uuid,
    action varchar(80) not null,
    target_type varchar(80) not null,
    target_id uuid,
    correlation_id uuid,
    created_at timestamptz not null
);
