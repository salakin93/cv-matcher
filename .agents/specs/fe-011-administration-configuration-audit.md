# FE-011 - Administration Configuration and Audit

## Objective

Deliver ADMIN-only operational configuration, safe integration health, and
paginated/filterable immutable audit consultation.

## References

- `docs/prd-011-administration-audit-and-notifications.md` configuration and audit rules.
- `docs/architecture.md` sections 5, 8, 9, and 11.
- Frontend planning documents, FE-011; backend specs 023-024; FE-002.

## Scope

### Included

- ADMIN configuration UI for server-allowlisted future AI model selection and
  global job concurrency 1-10 as returned/validated by API.
- Safe Outlook/Claude integration status and ADMIN audit list with generated
  pagination and action/actor/UTC-period filters.

### Excluded

- Secret/token/key/path editing or display, provider payloads, CV/document data,
  audit mutation/deletion, migration settings, manual email, and recruiter access.

## UX Behavior

- Configuration forms show only server-provided allowed model choices and generated
  concurrency constraints. Clearly state that changes apply to future work only;
  display API concurrency/version conflicts without overwriting server state.
- Integration panels show only safe health/status and action guidance. Audit is
  read-only: filters update the paginated API query and UTC dates are labelled as
  UTC. It renders only safe event fields supplied by API.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 023 and 024 for allowlisted
  configuration/current values, integration health, config update/conflict, audit
  query/pagination/filter values, and safe audit events. Do not invent endpoints,
  allowed models, integration fields, audit event schemas, or filter semantics.

## Routes, State, Accessibility, and Responsive Design

- Add ADMIN-only configuration, integrations, and audit routes/navigation; direct
  recruiter access receives standard denial. Model loading, empty audit, filtered
  empty, saving, conflict, safe health error, retry, denied, and expired session.
- Use labelled selects/number fields, keyboard-accessible pagination/filtering,
  semantic audit tables or responsive cards, UTC timezone text, accessible save
  result announcements, and focus-safe conflict feedback. Mobile keeps filter and
  event content readable without hidden columns that conceal required information.

## Frontend Security and Privacy

- Never request/display/log secrets, tokens, credentials, provider payloads, file
  paths, CVs, extracted text, or candidate PII. Do not put audit filters/results in
  persistent storage, URLs, telemetry, or console logs. Backend remains the final
  authorization and allowlist enforcement point.

## Configuration and Integrations

- Reuse the FE-001 API client and role guard. The backend is the single source of
  configuration values and integration status; this SPA introduces no environment
  settings, provider clients, or client-side allowlist.

## Data, Persistence, Errors, and Observability

- Keep configuration/audit state ephemeral. Normalize `401`, `403`, `409`, `422`,
  and safe server failures. Client diagnostics carry only approved technical event
  names and correlation IDs.

## Manual Validation

- With synthetic ADMIN/recruiter data, validate allowed model selection, concurrency
  boundaries supplied by API, future-work messaging, conflict reload, safe Outlook/
  Claude states, paginated audit filters/UTC display, recruiter denial, keyboard,
  mobile, slow network, and absence of secrets/PII in UI/browser logs.

## Deferred Automation

- Final stabilization: configuration and audit filter components, generated-
  contract tests, E2E role/config/conflict/audit pagination flows, security log/
  storage assertions, and accessibility/responsive regression coverage.

## Acceptance Criteria

1. Only ADMIN can view/update server-allowlisted configuration or view integration
   health and audit data.
2. The UI accepts only generated allowed model values and server-defined concurrency
   constraints, and says changes apply only to future jobs.
3. Audit is read-only, paginated/filterable, UTC-labelled, and contains only safe
   API fields.
4. No configuration, integration, or audit screen exposes secrets, provider payloads,
   CVs, storage details, or unauthorized PII.

## Dependencies and Risks

- Depends on FE-002 and backend 023-024 generated OpenAPI.
- RISK: safe integration-health and audit projection/filter contracts must be
  explicitly available before implementation.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: backend allowlists configuration and shapes audit data;
  frontend does not duplicate those rules.
- BLOCKER: requires approval and generated OpenAPI from backend 023-024.

## Definition of Ready

`BLOCKED` - FE-002 plus approved backend 023-024 generated OpenAPI.
