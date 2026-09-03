create table verification_resend_attempt (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    requested_at timestamptz not null
);

create index idx_verification_resend_attempt_user_requested
    on verification_resend_attempt(user_id, requested_at);
