create table outbox_message (
    id uuid primary key,
    recipient varchar(320) not null,
    purpose varchar(40) not null,
    payload_ciphertext bytea not null,
    encryption_key_version integer not null check (encryption_key_version > 0),
    created_at timestamptz not null,
    delivered_at timestamptz,
    attempts integer not null default 0 check (attempts >= 0)
);

create index idx_outbox_message_pending on outbox_message(created_at) where delivered_at is null;
