create index idx_user_account_active_admin on user_account(role) where status = 'ACTIVE';
