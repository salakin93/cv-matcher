# FE-008 - Report Exports and Notifications

## Objective

Add base full-report PDF/XLSX export and the authenticated in-app job-notification
inbox. Availability is intentionally not part of exports in this increment.

## References

- `docs/prd-008-report-filters-and-exports.md` export and audit rules.
- `docs/prd-011-administration-audit-and-notifications.md` notification rules.
- `docs/architecture.md` sections 7 and 9.
- Frontend planning documents, FE-008; backend specs 015 and 022; FE-006.

## Scope

### Included

- Request, status, terminal download, failure, and expiry handling for complete
  PDF/XLSX exports of a completed report.
- In-app notification inbox, unread/read presentation, and mark-read action for
  safe job completion, warning, and failure notifications.

### Excluded

- Availability in filters/exports, profile data, historical search, filtered or
  partial exports, manual email, templates, public links, CV exports, and admin
  audit UI. FE-009 adds availability export value.

## UX Behavior

- Export is explicitly labelled as the complete report regardless of active or
  future screen filters. Disable duplicate request activation while pending;
  display only API-safe queued/completed/failed/expired status and download when
  available. Do not promise an expired/failed export can be recovered.
- Notifications identify the vacancy and general job outcome only. Marking read
  changes notification state, never job/report/candidate data. Inbox may poll via
  its shared API utility until an approved push contract exists.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 015 and 022 for export request,
  status, authorized file delivery/expiry, notifications query, unread state, and
  mark-read. No endpoint, export payload, retention time, or notification schema
  is invented by frontend.

## Routes, State, Accessibility, and Responsive Design

- Extend FE-006 report actions and add protected notification inbox/navigation.
  Cover loading, empty, unread, request-pending, export terminal, expiry, retry,
  denied, and session-expired states.
- Export and read controls are keyboard-operable with live result status. The inbox
  uses semantic list controls and mobile-friendly readable cards; distinguish
  unread status without color alone.

## Frontend Security and Privacy

- Never persist/export/cache/log export bytes, download URLs, CV data, candidate
  details, notification payloads beyond rendered API fields, or audit information.
  Download is authenticated and no permanent/public link is displayed.

## Configuration and Integrations

- Reuse FE-001 API client; centralize notification polling rather than adding a
  provider or email client. The SPA does not send email or manage mail delivery.

## Data, Persistence, Errors, and Observability

- Keep export and inbox state ephemeral. Normalize safe `401`, `403`, `409`, and
  terminal errors; telemetry excludes report/candidate/export/notification content.

## Manual Validation

- With synthetic reports/jobs, validate PDF and XLSX request/completion/download,
  rapid double-click, failure/expiry, complete-export message despite filters,
  safe anonymized export behavior, notification unread/read, job outcome messages,
  authorization, keyboard, mobile, and slow network.

## Deferred Automation

- Final stabilization: export state and inbox component tests, generated-contract
  tests, E2E request/download/expiry/read flows, and accessibility/mobile tests.

## Acceptance Criteria

1. A valid report can request and download one complete authenticated PDF or XLSX
   export without a duplicate request from repeated activation.
2. FE-008 exports exclude availability; FE-009 is the only increment that adds it.
3. Notifications expose only a safe vacancy/general job outcome and can be marked
   read without mutating job data.
4. No export or notification UI reveals a public link, CV, sensitive candidate data,
   or provider detail.

## Dependencies and Risks

- Depends on FE-006 and backend 015/022 generated OpenAPI.
- RISK: asynchronous export state/expiry and notification pagination contracts must
  be available before implementation.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: exports are full immutable reports and do not use UI
  filters. Availability is deferred to FE-009.
- BLOCKER: requires approval and generated OpenAPI from backend 015 and 022.

## Definition of Ready

`BLOCKED` - FE-006 plus approved backend 015/022 generated OpenAPI.
