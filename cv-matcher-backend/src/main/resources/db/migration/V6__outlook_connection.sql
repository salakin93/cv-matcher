create table outlook_connection (
 id smallint primary key check (id=1), status varchar(40) not null check (status in ('NOT_CONNECTED','CONNECTED','REAUTHORIZATION_REQUIRED','ERROR')),
 tenant_id varchar(120), account_subject varchar(255), granted_scopes text[] not null default '{}', refresh_token_ciphertext bytea,
 refresh_token_key_version integer not null check (refresh_token_key_version>0), connected_at timestamptz, last_token_refresh_at timestamptz,
 updated_at timestamptz not null, last_error_code varchar(80), version bigint not null default 0,
 constraint chk_outlook_connected check (status <> 'CONNECTED' or refresh_token_ciphertext is not null),
 constraint chk_outlook_reauth check (status <> 'REAUTHORIZATION_REQUIRED' or refresh_token_ciphertext is null)
);
insert into outlook_connection(id,status,granted_scopes,refresh_token_key_version,updated_at,version) values(1,'NOT_CONNECTED','{}',1,current_timestamp,0) on conflict (id) do nothing;
create table outlook_authorization_attempt (
 id uuid primary key, state_hash bytea not null unique, code_verifier_ciphertext bytea not null, nonce_hash bytea not null,
 initiated_by_user_id uuid not null references user_account(id), expires_at timestamptz not null, created_at timestamptz not null, consumed_at timestamptz
);
create index idx_outlook_auth_attempt_expiry on outlook_authorization_attempt(expires_at);
