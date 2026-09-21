# FE-007 - Human Status and CV Download

## Objective

Add human operational status controls to report candidates and protected download
of the exact CV used for that report entry.

## References

- `docs/prd-006-human-status-by-report.md`.
- `docs/prd-007-protected-cv-download.md`.
- `docs/architecture.md` sections 5, 6, and 9.
- Frontend planning documents, FE-007; backend specs 013-014; FE-006.

## Scope

### Included

- Shared candidate-report status selector for `PENDIENTE`, `EN_REVISION`,
  `PRESELECCIONADO`, and `DESCARTADO`, including generated version conflicts.
- Authenticated download initiated only from the exact report candidate entry.

### Excluded

- Comments, reasons, assignments, automated decisions, status history for normal
  recruiters, profile editing, directory downloads, exports, permanent links, and
  downloads by document/profile UUID.

## UX Behavior

- The current human status is visibly separate from score and analysis. Any allowed
  value can be selected directly; no status is suggested by the system.
- On conflict, retain the attempted selection visibly, show a reload requirement,
  and never auto-merge. A successful change refreshes the entry shared state.
- A download action requests the generated authenticated file operation. Prevent
  duplicate clicks while pending; do not retain the response URL or file. Show a
  safe unavailable/rate-limit error without document details.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 013 and 014 for report-candidate
  human status/version/conflict and authorized file delivery/unavailability/rate
  limit. Do not invent endpoints, content-disposition handling, or document IDs.

## Routes, State, Accessibility, and Responsive Design

- Extend only the FE-006 report candidate view; no standalone document route.
  Model idle, updating, conflict, downloaded, unavailable, rate-limited, denied,
  and expired-session states.
- Status controls have labels and keyboard support; download progress/errors are
  announced. Mobile keeps status and download separate from score controls with
  adequate touch targets.

## Frontend Security and Privacy

- Do not persist, preview, cache, log, or expose CV bytes, download URLs, original
  filenames, document IDs, storage references, or file errors. Browser download is
  authorized by backend; UI authorization never replaces server enforcement.

## Configuration and Integrations

- Reuse the FE-001 API client and FE-006 report state. No storage SDK or new
  frontend configuration is introduced.

## Data, Persistence, Errors, and Observability

- Keep status/version and download pending state in memory. Normalize `401`,
  `403`, `409`, and safe availability/rate-limit responses without PII telemetry.

## Manual Validation

- With synthetic reports/files, validate each direct status change, shared refresh,
  concurrent update conflict, isolation across reports, permitted PDF/DOCX download,
  unavailable/trashed file, rate limit, unauthorized request, keyboard, mobile,
  and no retained URL/file state.

## Deferred Automation

- Final stabilization: status/conflict component tests, generated-contract tests,
  E2E for isolated status/download/rate limit, and accessibility/responsive checks.

## Acceptance Criteria

1. Recruiters and admins can set a human status without changing analysis,
   ranking, profile, or any other report.
2. A stale status update requires reload and does not overwrite the saved value.
3. Only the report-entry action can request the associated authorized CV.
4. The browser never exposes or retains a public/permanent CV link or file metadata.

## Dependencies and Risks

- Depends on FE-006 and backend 013-014 generated OpenAPI.
- RISK: browser file response semantics must be documented in OpenAPI/supporting
  contract without exposing storage implementation.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: status is a candidate-report relation, not profile state.
- BLOCKER: requires approval and generated OpenAPI from backend 013-014.

## Definition of Ready

`BLOCKED` - FE-006 plus approved backend 013-014 generated OpenAPI.
