# FE-002 - Account Administration

## Objective

Extend the authenticated React application with password recovery, voluntary
password/email changes, and ADMIN-only account administration.

## References

- `docs/prd-001-access-users-administration.md` FR-ACC-015 through FR-ACC-028.
- `docs/architecture.md` sections 5 and 9.
- Frontend planning documents, FE-002; backend specs 001 and 002.

## Scope

### Included

- Spanish recovery request and reset flows; authenticated voluntary password and
  email-change flows.
- ADMIN user list with backend pagination/filtering, role and activation actions,
  confirmations, and visible optimistic-concurrency/last-ADMIN conflicts.

### Excluded

- Manual account creation, invitations, account deletion, administrative password
  or email changes, MFA, SSO, and navigable audit history.

## UX Behavior

- Recovery always shows the neutral outcome. Expired, used, or replaced links use
  safe API feedback and offer a new request.
- Successful reset, password change, or verified email change redirects to login
  because backend sessions are revoked; it never signs the user in automatically.
- The ADMIN table does not offer self-role/self-activation controls. A rejected
  last-active-ADMIN change explains the safe conflict and refreshes server data;
  it never overwrites concurrent changes.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 001 and 002 for current user,
  recovery/reset, password/email change and verification, and ADMIN user query
  and mutation operations.
- Use generated pagination, version/concurrency, role, state, and error models;
  do not infer endpoints or reproduce server account rules.

## Routes, State, Accessibility, and Responsive Design

- Add public recovery/reset routes and authenticated account-security routes.
  Mount administration only behind the FE-001 ADMIN route guard and hide its
  navigation from recruiters.
- Use server pagination/filter state in the page state, not URLs when it could
  expose personal data. Handle loading, empty, validation, conflict, retry, and
  session-revoked states.
- Tables have labelled filters, keyboard-operable pagination and actions, dialog
  focus trapping/return, and accessible mutation announcements. On mobile use
  readable row cards or an equivalent accessible responsive representation.

## Frontend Security and Privacy

- Display only account fields returned by OpenAPI and needed for administration.
  Never render sessions, hashes, passwords, tokens, verification values, or raw
  errors; do not log recovery/email values or user-list data.

## Configuration and Integrations

- Reuse FE-001's single generated API client and auth/error handling. No new
  browser configuration, storage, provider integration, or secret is introduced.

## Data, Persistence, Errors, and Observability

- Keep forms and user data in ephemeral component/query state. `401` clears the
  session, `403` shows denial, and `409` reloads the affected account/list.
- Record no account PII in client telemetry; only approved technical event names
  and safe correlation IDs may be emitted.

## Manual Validation

- Validate neutral recovery for synthetic existing/non-existing accounts, invalid
  and used reset links, session revocation after each applicable change, and new
  email verification.
- Validate ADMIN pagination/filtering, role/activation confirmation, self-action
  absence, last-ADMIN conflict, recruiter `403`, keyboard dialogs, and mobile.

## Deferred Automation

- Final stabilization: component/contract tests for neutral recovery and
  conflicts; E2E for reset, revocation, email change, ADMIN authorization, and
  last-ADMIN protection; accessibility and responsive table coverage.

## Acceptance Criteria

1. Recovery does not reveal whether an active account exists.
2. A successful security change requiring session revocation returns the user to
   login without protected data remaining visible.
3. Only ADMIN can manage other accounts, and the UI cannot submit self changes.
4. Concurrent or last-ADMIN conflicts are visible and require reload before retry.

## Dependencies and Risks

- Depends on FE-001 and backend 002 generated OpenAPI; backend 001 remains a
  transitive dependency.
- RISK: user-list fields and filter semantics must be minimized by backend schema.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: all account administration models come from generated
  OpenAPI; no frontend-owned account DTOs.
- BLOCKER: requires approval and generated OpenAPI from backend 001/002.

## Definition of Ready

`BLOCKED` - FE-001 foundation and approved generated OpenAPI from backend 001/002.
