# FE-003 - Vacancy Management

## Objective

Provide shared vacancy list, detail, creation, complete editing, archiving, and
reactivation for authenticated recruiters and administrators.

## References

- `docs/prd-002-vacancies-async-jobs.md` sections 1 through 4.
- `docs/architecture.md` sections 3, 7, and 9.
- Frontend planning documents, FE-003; backend specs 001 and 003.

## Scope

### Included

- Vacancy list/detail, create/edit form, ordered 1-30 requirement editor, active
  title-duplicate warning, archive/reactivate actions, and concurrency handling.

### Excluded

- Report job creation/status, report results, candidate data, historical search,
  export, and frontend scoring or UTC range calculation.

## UX Behavior

- The form captures date-only values labelled as Bolivia time, title, description,
  and requirement description, integer weight, mandatory flag, and order.
- Local feedback aids entry, but backend validation decides validity. A duplicate
  active title is a non-blocking warning exactly when returned by the API.
- Archived vacancies are read-only. Archive/reactivate ask for confirmation where
  appropriate and refresh the resource after success. A version conflict preserves
  unsaved local work visibly and requires the user to reload before submitting.

## API Contract Dependencies

- Consume only generated OpenAPI from backend spec 003 for vacancy query, detail,
  create, complete update, archive/reactivate, duplicate-title warning, versions,
  and API validation/conflict responses.
- Send the generated expected-version field when required. Do not construct UTC
  timestamps, derive archive state, or invent vacancy endpoints/types.

## Routes, State, Accessibility, and Responsive Design

- Add protected recruiter/admin list, create, and detail/edit routes. Preserve no
  draft in persistent browser storage.
- Cover loading, empty list, validation, duplicate warning, archived, `409`,
  retry, and `401`/`403` states. Use accessible ordered controls for requirements,
  labelled fields/error associations, keyboard archive dialogs, and focus return.
- Desktop supports dense editing; mobile uses a single-column editor with visible
  reorder controls, large touch targets, and no reliance on drag-only interaction.

## Frontend Security and Privacy

- Render only vacancy content returned by the API. Do not log form content,
  versions, errors, or IDs to telemetry; do not place protected content in URLs
  except the opaque route identifier required by generated navigation.

## Configuration and Integrations

- Reuse FE-001 API/auth configuration. No external integration, secret, or new
  frontend configuration point is introduced.

## Data, Persistence, Errors, and Observability

- State is ephemeral. Normalize `422` field errors and `409` version conflicts;
  server data is re-fetched rather than automatically merged or overwritten.

## Manual Validation

- With synthetic vacancies, validate create, ordered requirements, boundaries
  1/30 and weights 1/5, invalid dates, duplicate warning, edit, archive/reactivate,
  concurrent edit conflict, keyboard use, responsive layout, and authorization.

## Deferred Automation

- Final stabilization: form/component tests, generated-contract tests, E2E for
  CRUD/archive/conflict, and accessibility/responsive regression tests.

## Acceptance Criteria

1. Recruiters and admins can manage shared valid vacancies with ordered
   requirements and Bolivia date-only input.
2. A duplicate active title warns without blocking a valid save.
3. Archived vacancies cannot be edited or used by this UI to start work.
4. A stale update never overwrites another user's saved vacancy change.

## Dependencies and Risks

- Depends on FE-001 and backend 003 generated OpenAPI.
- RISK: exact API representation of date-only Bolivia values must be published;
  frontend must not assume a timestamp encoding.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: server/OpenAPI owns date conversion and conflict shape.
- BLOCKER: requires approval and generated OpenAPI from backend 003.

## Definition of Ready

`BLOCKED` - approve backend 003 and publish generated OpenAPI with its date and concurrency contract.
