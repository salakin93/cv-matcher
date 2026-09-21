# FE-009 - Shared Profiles and Historical Search

## Objective

Add shared candidate profiles, availability-aware report filtering/export value,
and explicitly confirmed asynchronous historical search.

## References

- `docs/prd-009-shared-profiles-and-historical-search.md`.
- `docs/prd-008-report-filters-and-exports.md` filter base rules.
- `docs/architecture.md` sections 7 and 9.
- Frontend planning documents, FE-009; backend specs 016-018; FE-006 and FE-008.

## Scope

### Included

- Shared profile directory/detail; availability update; location and extracted-skill
  corrections with version conflict feedback.
- Report-local score, mandatory-compliance, warning, insufficient-evidence,
  availability, and text filters; FE-009 adds current availability to export value.
- Historical-search eligibility display, optional generated filters, explicit final
  confirmation, job state, safe completion/failure, and combined-report navigation.

### Excluded

- Manual identity/name/email correction or merge, automatic historical search,
  directory CV download, trash/privacy, changing completed reports, or availability
  in FE-008's base export before this increment.

## UX Behavior

- Profile edits show current shared values and use generated version data. Conflict
  preserves the attempted edit and requires reload; changes never imply historical
  report recalculation.
- Report filters reduce only the open report display and combine with AND. Multiple
  availability values match OR. Clear filters restores entries; filters do not
  cause historical lookup. Text filter is limited to the open report entries.
- Historical search is offered only when API says the report is eligible. Before
  request, present a confirmation that clearly states CV analysis will occur;
  invoke the backend durable-confirmation API and do not create a job until it
  confirms success. Reuse job progress patterns without assuming normal
  report-job endpoints or state types.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 016-018 for profiles/corrections,
  current availability, report filters/export augmentation, historical eligibility,
  confirmation/request, job state, and combined report result. Do not invent
  endpoints, eligibility calculations, candidate queries, or 500-CV limit logic.

## Routes, State, Accessibility, and Responsive Design

- Add protected directory/profile routes and extend FE-006 report filters/export
  presentation. Add a historical-search confirmation and status view tied to source
  report navigation, not a global search route.
- Handle loading, empty, no-match, ineligible, eligible-unconfirmed, confirming,
  queued/running, warnings, no-result, conflict, retry, denial, and expiry states.
- Use labelled multi-select/filter controls, accessible results counts, keyboard
  confirmation dialog with explicit action text, focus restoration, and responsive
  cards/forms that remain usable on narrow screens.

## Frontend Security and Privacy

- Do not put name, email, skills, location, availability, search terms, filters,
  or profile data in persistent storage, telemetry, console logs, or URLs. Render
  only authorized API data and no CV/text extraction/document fields.

## Configuration and Integrations

- Reuse FE-001 API client and central job polling; no browser AI, document, or
  external search client is introduced.

## Data, Persistence, Errors, and Observability

- Query/filter and edit state is ephemeral. Normalize `401`, `403`, `409`, `422`,
  and safe job errors; no client events contain profile or search content.

## Manual Validation

- With synthetic shared profiles/reports, validate availability and corrections,
  concurrent conflict, report-local combined filters/clear, current availability
  in export only after FE-009, eligibility/no eligibility, cancellation before
  confirmation, confirmed historical job, partial/no-result outcome, original
  report immutability, keyboard confirmation, mobile, and authorization.

## Deferred Automation

- Final stabilization: profile/filter/confirmation components, generated-contract
  tests, E2E conflict and historical search lifecycle, privacy URL/log assertions,
  and accessibility/responsive regression coverage.

## Acceptance Criteria

1. Shared availability/location/skills updates affect future views only and do not
   alter completed report analysis or rankings.
2. Availability filters and export value use current profile availability, with
   `DESCONOCIDO` when supplied by API.
3. Historical search cannot start before backend durable confirmation succeeds and
   only appears for API-confirmed eligible reports.
4. A successful search navigates to a new combined immutable version; the source
   report remains unchanged.

## Dependencies and Risks

- Depends on FE-006, FE-008, and backend 016-018 generated OpenAPI.
- RISK: contract must distinguish source report, historical job, no-result, and
  combined-version outcomes without exposing ineligible candidate data.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: eligibility, filters applied to data, search-job
  creation, and durable confirmation are backend authority. The frontend invokes
  the confirmation API and must not create a job until that API confirms success.
- BLOCKER: requires approval and generated OpenAPI from backend 016-018.

## Definition of Ready

`BLOCKED` - FE-006/FE-008 plus approved backend 016-018 generated OpenAPI.
