create function outlook_granted_scopes_are_canonical(scopes text[])
returns boolean
language sql
immutable
strict
parallel safe
return scopes = array(
    select distinct scope collate "C"
    from unnest(scopes) as scope
    where scope = btrim(scope)
      and btrim(scope) <> ''
    order by scope collate "C"
);

alter table outlook_connection
    add constraint chk_outlook_granted_scopes_canonical
        check (outlook_granted_scopes_are_canonical(granted_scopes)),
    add constraint chk_outlook_connected_required_fields
        check (status <> 'CONNECTED' or (
            refresh_token_ciphertext is not null
            and nullif(btrim(tenant_id), '') is not null
            and nullif(btrim(account_subject), '') is not null
            and cardinality(granted_scopes) > 0
        ));
