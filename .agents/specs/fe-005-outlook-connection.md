# FE-005 - Outlook Connection

## Objective

Provide an ADMIN-only React screen to view the safe shared Outlook connection
state and begin or reauthorize the backend-owned OAuth flow.

## References

- `docs/prd-003-outlook-message-discovery.md` sections 1 through 3.
- `docs/architecture.md` sections 5.3, 8.1, and 9.
- Frontend planning documents, FE-005; backend specs 001 and 005; FE-001.

## Scope

### Included

- ADMIN route/navigation, safe connection status, start/reauthorization action,
  return outcome handling, and state refresh.

### Excluded

- Mailbox/message/Inbox views, provider configuration, tenant identity, tokens,
  client secrets, OAuth code/state storage, and recruiter access.

## UX Behavior

- Display only generated safe states: `NOT_CONNECTED`, `CONNECTED`,
  `REAUTHORIZATION_REQUIRED`, and `ERROR`, plus safe date/code fields supplied
  by API. Give clear Spanish next steps without technical/provider detail.
- The browser navigates only to a backend-provided authorization destination. The
  backend handles callback validation; on return, the SPA discards callback query
  data, shows a safe outcome, and refreshes status. A failed reauthorization must
  not claim that an existing connection was replaced.

## API Contract Dependencies

- Consume generated OpenAPI from backend spec 005 for safe connection status and
  authorization initiation/return status. Do not hard-code OAuth paths, create
  Microsoft requests, retain `code`/`state`, or model credentials in TypeScript.

## Routes, State, Accessibility, and Responsive Design

- The protected ADMIN route is guarded by FE-001 role state; recruiters have no
  navigation entry and receive the normal `403` view for direct access.
- Handle loading, no connection, connected, reauthorization required, safe error,
  pending navigation, return, retry, and expired session. Use accessible status
  announcements and labelled action buttons. On mobile, status and primary action
  remain visible without horizontal layout dependence.

## Frontend Security and Privacy

- Never persist, render, log, or send OAuth codes, state, tokens, mailbox email,
  tenant, provider payloads, callback URLs, or secrets. Scrub callback query data
  from browser history after it has been handled.

## Configuration and Integrations

- Reuse FE-001 generated API client and role guard. OAuth remains an integration
  exclusively between browser navigation and backend; no provider SDK/client is
  added to the SPA.

## Data, Persistence, Errors, and Observability

- Keep only current safe API status in memory. Normalize auth and safe API errors;
  client telemetry excludes provider and authorization data.

## Manual Validation

- Use a backend double/sandbox, never production credentials, to validate each
  status, successful return, cancelled/failed return with existing connection,
  `ERROR`, recruiter denial, browser-history cleanup, keyboard, mobile, and
  expired-session behavior.

## Deferred Automation

- Final stabilization: route/role component tests, generated-contract tests, E2E
  with backend OAuth double for return paths, URL-scrubbing tests, and a11y/mobile
  coverage.

## Acceptance Criteria

1. Only ADMIN can view or initiate the shared Outlook connection flow.
2. The screen shows only safe status data and no credential/provider identity.
3. The SPA never retains OAuth callback or credential values after return.
4. A failed reauthorization does not falsely represent the prior connection state.

## Dependencies and Risks

- Depends on FE-001 and backend 005 generated OpenAPI.
- RISK: final backend return/callback UX contract must provide a safe SPA outcome.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: backend is the confidential OAuth client and callback
  endpoint; SPA only follows backend-issued navigation.
- BLOCKER: requires approval and generated OpenAPI from backend 005.

## Definition of Ready

`BLOCKED` - FE-001 and approved generated OpenAPI from backend 005.
