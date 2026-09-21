# FE-001 - Access and Authentication

## Objective

Deliver the React SPA foundation and Spanish public/authenticated access flows for
registration, email verification, login, refresh, logout, account lock feedback,
and mandatory first-ADMIN password change.

## References

- `docs/PRD.md` sections 2, 3, and 10.
- `docs/prd-001-access-users-administration.md` FR-ACC-001 through FR-ACC-014 and FR-ACC-029 through FR-ACC-031.
- `docs/architecture.md` sections 3, 5, and 9.
- `docs/FRONTEND_PHASE_SPECS.md` and `docs/FRONTEND_ROADMAP.md` FE-001.
- Backend spec `001-identity-account-foundation.md`.

## Scope

### Included

- React 19, strict TypeScript, Vite, Tailwind CSS, shadcn/ui, application shell,
  generated OpenAPI types, and the central authenticated API client.
- Spanish registration, verification and resend, login, refresh, logout, pending
  account and lock feedback, and forced-password-change flow for the initial ADMIN.
- Public routes for access flows and an authenticated layout with recruiter/admin
  navigation placeholders only.

### Excluded

- Password recovery, voluntary password or email change, user administration,
  profile editing, MFA, SSO, and all vacancy features. These belong to FE-002+
  or are out of product scope.

## UX Behavior

- Forms provide local required/format feedback only; the backend remains the
  authority for password policy, account state, rate limits, and authorization.
- Registration and resend display the neutral API response. Verification never
  creates a session and directs the user to sign in.
- A pending account with correct credentials receives the permitted verification
  guidance; generic failures do not reveal account existence or activation.
- A locked account displays the safe recovery guidance. Logout removes in-memory
  access state and returns to login. A refresh failure or any protected `401`
  does the same and clears protected screen data.
- An initial ADMIN with mandatory password change can access only that form or
  logout; after success, the revoked session requires a new login.

## API Contract Dependencies

- Consume only OpenAPI generated from backend spec 001, including registration,
  verification/resend, login, refresh, logout, current-session identity, and
  mandatory password-change operations when published by the backend.
- Do not hard-code endpoint paths, request/response models, token fields, error
  codes, or expiry calculations. The generated schema and safe API messages are
  the frontend contract.

## Routes, State, Accessibility, and Responsive Design

- Public routes cover registration, verification, login, and required password
  change. Authenticated routes use a route guard based on in-memory session and
  generated current-user data; visual guards never replace backend authorization.
- Keep the access token only in memory. Refresh is initiated through the backend
  secure cookie; do not expose cookie contents to JavaScript.
- Every async view provides loading, submit-pending, success, safe error, and
  retry states. Announce result changes with accessible status messaging, focus
  the first invalid field or error summary, and support keyboard-only operation.
- Desktop-first layouts remain usable at narrow widths with a single-column form,
  visible labels, adequate touch targets, contrast, and no horizontal clipping.

## Frontend Security and Privacy

- Never persist JWTs, refresh tokens, passwords, verification/reset values,
  secrets, or account data in localStorage, sessionStorage, URLs, telemetry, or
  client logs.
- Do not render raw API failures or correlation details beyond the safe message
  and correlation ID supplied for support. Never log credentials or auth payloads.

## Configuration and Integrations

- The typed API client, authentication state, error normalization, and OpenAPI
  generation location are established once in `cv-matcher-frontend/` by this
  increment. No provider client is added; browser code never calls Outlook or
  Claude.

## Data, Persistence, Errors, and Observability

- Frontend state is ephemeral except for non-sensitive presentation preference if
  later approved. Do not cache protected responses persistently.
- Normalize generated API `401`, `403`, `409`, and `422`; only 401 changes the
  local session. Surface server correlation IDs without sensitive payloads.
- Client observability, if introduced, records only technical event names and
  correlation IDs, never account identifiers, email addresses, tokens, or forms.

## Manual Validation

- Using synthetic accounts and test mail delivery, validate register -> verify ->
  login, neutral duplicate registration, pending login, lock feedback, logout,
  expired/revoked session, and initial-ADMIN restriction.
- Validate keyboard, screen-reader announcements, desktop and mobile layouts,
  slow/failing network feedback, and direct navigation to a protected route.

## Deferred Automation

- Final stabilization: component tests for validation/state transitions; contract
  tests against generated OpenAPI; E2E tests for registration, verification,
  refresh/logout, lock, expiry, role guards, and mandatory password change;
  accessibility and responsive regression coverage.

## Acceptance Criteria

1. A visitor can register, verify, and explicitly log in with a Spanish UI.
2. Pending, generic failed, and locked login feedback follows the backend-safe
   response and does not expose account existence.
3. Expired, revoked, or logged-out sessions expose no protected route content.
4. The initial ADMIN cannot use product routes before completing required password
   change.
5. No authentication material or sensitive form data is persistently stored or
   logged by the browser.

## Dependencies and Risks

- Depends on backend 001 delivering approved generated OpenAPI and safe auth
  error semantics.
- FE-002 through FE-011 depend on this foundation.
- RISK: browser refresh/session bootstrap behavior must follow the final backend
  refresh contract without extending the absolute server-side session lifetime.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: generated OpenAPI is the only SPA API type source.
- BLOCKER: FE-001 waits for backend 001 approval and publication of its generated
  OpenAPI contract; no published generated contract is available for implementation.

## Definition of Ready

`BLOCKED` - approve backend 001 and publish its generated OpenAPI contract.
