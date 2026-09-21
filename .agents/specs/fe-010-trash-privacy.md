# FE-010 - Trash and Privacy Deletion

## Objective

Add shared CV trash/restore views and the ADMIN-only immediate, irreversible
privacy-deletion workflow with safe status feedback.

## References

- `docs/prd-010-trash-and-privacy-deletion.md`.
- `docs/architecture.md` sections 5, 6, and 9.
- Frontend planning documents, FE-010; backend specs 019-021; FE-007 and FE-009.

## Scope

### Included

- Move an eligible CV to shared trash, trash listing, retention/expiry display as
  supplied by API, restore before expiry, and unavailable download state.
- ADMIN privacy-deletion initiation with strong explicit confirmation, processing,
  completed, and safe failed/blocked state display.

### Excluded

- Restoration after purge/privacy deletion, PII recovery, trash export, recruiter
  privacy deletion, selective profile deletion, score changes, or manual purge.

## UX Behavior

- Trash actions clearly state future exclusion from downloads/reports/search and
  that completed report versions remain unchanged. Restore is offered only when
  the API says it is allowed; expired/purged entries never offer recovery.
- Privacy deletion is visible only to ADMIN. Its confirmation plainly states
  immediate irreversibility, scope across candidate data, and historical
  anonymization. Completion is not shown until API confirms it; a safe failure
  warns that access remains blocked without revealing personal/technical details.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 019-021 for trash eligibility/list,
  move/restore/expiry/purge state, privacy deletion target/confirmation/request,
  processing/completion/failure, and resulting safe report presentation. Do not
  invent endpoints, deletion identifiers, retention countdown, or purge behavior.

## Routes, State, Accessibility, and Responsive Design

- Add protected trash routes/actions from report/profile contexts; mount privacy
  deletion under ADMIN guard. Cover loading, empty, trashed, restorable, expired,
  moving/restoring, confirmation, processing, completed, safe failure, denial,
  and session expiry.
- Destructive actions require accessible confirmation dialogs with explicit labels,
  keyboard focus management, announced outcome, and no color-only irreversibility
  cue. Mobile maintains distinct destructive/restore controls and readable dates.

## Frontend Security and Privacy

- Do not expose CV/document/profile internals, deletion evidence, storage paths,
  or raw errors. On privacy deletion, immediately clear related in-memory views;
  never cache, log, or retain deleted PII. Route guards do not replace backend ADMIN authorization.

## Configuration and Integrations

- Reuse FE-001 API/auth and FE-007 protected-download state patterns. No client
  file storage, purge scheduler, or secrets/configuration is introduced.

## Data, Persistence, Errors, and Observability

- All trash/privacy state is fetched from API and ephemeral. Normalize safe auth,
  conflict, validation, and processing errors. Client telemetry must omit candidate
  identifiers and deletion content.

## Manual Validation

- With synthetic candidates/documents, validate trash exclusion/unavailable
  download, shared restore before expiry, expired/purged no-restore state,
  completed-report preservation, recruiter privacy denial, ADMIN strong
  confirmation, processing/safe failure/completion, anonymized report rendering,
  keyboard dialogs, mobile, and session loss.

## Deferred Automation

- Final stabilization: destructive-dialog/state components, generated-contract
  tests, E2E trash/restore/privacy processing, cache-clearing/privacy assertions,
  and accessibility/responsive coverage.

## Acceptance Criteria

1. Trashed CVs are presented as unavailable for download and future operations,
   while completed report versions remain visible as API returns them.
2. Restore is available only before API-defined expiry; no UI promises recovery
   after purge.
3. Only ADMIN can initiate clearly irreversible privacy deletion.
4. Privacy deletion completion clears in-memory PII and renders historical entries
   only as safely anonymized API data.

## Dependencies and Risks

- Depends on FE-007, FE-009, and backend 019-021 generated OpenAPI.
- RISK: API must provide safe asynchronous privacy status and anonymized report
  response without identifiers that allow stale UI data to be retained.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: retention, purge, privacy scope, and access blocking are
  backend authority; the SPA communicates but does not emulate them.
- BLOCKER: requires approval and generated OpenAPI from backend 019-021.

## Definition of Ready

`BLOCKED` - FE-007/FE-009 plus approved backend 019-021 generated OpenAPI.
