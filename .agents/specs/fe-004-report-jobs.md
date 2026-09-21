# FE-004 - Report Jobs

## Objective

Let authorized users request, observe, cancel, and retry durable asynchronous
report jobs from a vacancy without blocking the browser.

## References

- `docs/prd-002-vacancies-async-jobs.md` sections 5 through 10.
- `docs/prd-003-outlook-message-discovery.md` sections 6 through 10.
- `docs/prd-004-secure-cv-ingestion.md` sections 8 and 9.
- `docs/architecture.md` sections 7 and 9.
- Frontend planning documents, FE-004; backend specs 004 through 006; FE-003.

## Scope

### Included

- Job request with generated threshold input if supplied by contract, vacancy job
  history/detail, safe counts/warnings, polling with backoff, cancel, retry, and
  navigation to a completed report when the API supplies one.

### Excluded

- Inbox/message/attachment views, Graph identifiers, document names/content,
  worker controls, report ranking UI, push transport, and notifications inbox.

## UX Behavior

- Job creation immediately presents accepted/queued state; the SPA never waits for
  Outlook, document processing, Claude, or ranking.
- Render only API status, terminal outcome, aggregate counts, safe warnings, and
  safe failure codes. Cancel is available only for API-designated active states;
  retry only for `FAILED` and `REAUTHORIZATION_REQUIRED`.
- Disable duplicate submission while a request is pending, reconcile `409` active-
  job conflicts by refreshing history, and stop polling when leaving the view,
  logging out, or reaching terminal state. `REAUTHORIZATION_REQUIRED` links only
  to the existing ADMIN Outlook route where authorized.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 004-006 for job create/list/detail,
  cancel/retry, status/counters/warnings, terminal report reference, and allowed
  actions. Do not infer transitions, poll intervals, endpoint paths, or Graph data.

## Routes, State, Accessibility, and Responsive Design

- Extend FE-003 vacancy detail with a job section and protected job detail/history
  routes. Model initial, accepted, polling, warning, terminal, conflict, retry,
  and unavailable states explicitly.
- Status changes use non-disruptive live announcements; actions have labels and
  confirmations. Mobile prioritizes current status and actions before secondary
  counts/history, while retaining keyboard access to all details.

## Frontend Security and Privacy

- Never render or log leases, worker metadata, Outlook IDs, email metadata,
  attachment names, document references, provider payloads, tokens, or CV data.
- Browser polling uses the authenticated API client only and stops on `401`.

## Configuration and Integrations

- Reuse FE-001 client configuration. Polling/backoff is a single frontend job-
  state utility, not duplicated by screens. No browser Graph or Claude client.

## Data, Persistence, Errors, and Observability

- Keep job state in memory and refresh from API as authority. Normalize `401`,
  `403`, `409`, `422`, and safe server errors; emit no PII in client diagnostics.

## Manual Validation

- With backend doubles and synthetic data, validate accepted request, one-active-
  job conflict, each visible status, partial warning, cancellation, allowed and
  disallowed retry, polling cleanup/backoff, navigation on completion, access
  denial, keyboard announcements, mobile, and slow/offline retry feedback.

## Deferred Automation

- Final stabilization: state-machine component tests, fake-timer polling tests,
  generated-contract tests, E2E for request/cancel/retry/conflict, and a11y/mobile
  regressions.

## Acceptance Criteria

1. Creating a job never blocks the UI on external processing.
2. The UI presents only safe, API-authoritative job status/counters/warnings.
3. Cancel and retry are available only when the API permits them, and double
   activation does not create duplicate requests.
4. Polling ends safely on terminal state, unmount, and lost session.

## Dependencies and Risks

- Depends on FE-003 and backend 004-006 generated OpenAPI.
- RISK: contract must identify safe polling data and terminal report navigation.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: polling with backoff is used until an approved push
  contract exists.
- BLOCKER: requires approval and generated OpenAPI from backend 004-006.

## Definition of Ready

`BLOCKED` - FE-003 plus approved generated OpenAPI from backend 004-006.
