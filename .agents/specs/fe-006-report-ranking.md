# FE-006 - Report Ranking

## Objective

Render immutable completed report versions in Spanish, including the backend
ranking, Top 5, scores, per-requirement assessments, threshold, and safe warnings.

## References

- `docs/prd-005-extraction-ai-scoring-ranking.md` sections 6 through 10.
- `docs/architecture.md` sections 7 and 9.
- Frontend planning documents, FE-006; backend specs 009-012; FE-004.

## Scope

### Included

- Report-version navigation, report summary, backend ranking and Top 5, candidate
  identity permitted by API, score breakdown, assessments/evidence/explanations,
  threshold indicator, and safe warning/empty states.

### Excluded

- Filter controls, human status, CV download, exports, availability, profile
  editing, historical search, trash, and any score/ranking mutation.

## UX Behavior

- Render candidate order, rank, scores, threshold, Top 5, identity confidence,
  `CUMPLE`, `NO_CUMPLE`, and `NO_DEMOSTRADO` exactly as returned. The threshold
  is informational and does not hide candidates.
- Distinguish a completed report with safe warnings from a completed-with-warnings
  job that has no report because no candidate was rankable. Never present an empty
  ranking as a report result when API says no report exists.
- Use progressive disclosure for assessment evidence/explanation while retaining
  the complete API result. Do not offer editing, sorting, or recalculation.

## API Contract Dependencies

- Consume generated OpenAPI from backend specs 009-012 for immutable report
  versions, summaries, ordered entries, Top 5, scores, assessments, threshold,
  safe warnings, and job-to-report navigation. Do not invent report endpoints or
  derive scores, ties, rank, candidate identity, or absence-of-report semantics.

## Routes, State, Accessibility, and Responsive Design

- Add protected report/version routes from FE-004 terminal navigation. State
  covers loading, report absent, empty permitted collection, warnings, retry,
  denied, and expired session.
- Use semantic tables or labelled responsive equivalents, descriptive score text
  in addition to color, keyboard-operable evidence disclosure, announced warning
  state, and focus-preserving version navigation. On mobile, show rank/name/total
  score first and allow accessible detail expansion without clipped data.

## Frontend Security and Privacy

- Render only identity, assessments, and evidence returned by the report contract.
  Never render CV/text extraction, sender data, prompts, provider payloads, hashes,
  storage paths, internal IDs, tokens, or raw error bodies; do not log report data.

## Configuration and Integrations

- Reuse the FE-001 generated API client and FE-004 report navigation. No provider
  call, scoring library, client-side ranking logic, or new configuration is added.

## Data, Persistence, Errors, and Observability

- Report state is read-only and ephemeral. Normalize API errors; `401` clears
  protected data. Client events contain only safe technical labels/correlation IDs.

## Manual Validation

- With synthetic reports, validate backend order/ties, fewer than five entries,
  threshold indicator, all assessment states, safe warnings, anonymized candidate
  label, no-rankable/no-report job outcome, keyboard/reader behavior, mobile, and
  denial/expired session.

## Deferred Automation

- Final stabilization: report rendering and disclosure component tests, generated-
  contract tests, E2E terminal-job-to-report flow, sorting-regression tests, and
  accessibility/responsive visual coverage.

## Acceptance Criteria

1. The display matches backend rank, scores, tie outcomes, and Top 5 without
   frontend calculation or reorder.
2. `NO_DEMOSTRADO` candidates remain visible and are distinguishable from
   `NO_CUMPLE`.
3. Safe warning and no-rankable terminal outcomes are accurately distinguished.
4. The screen exposes no excluded CV, provider, storage, or internal data.

## Dependencies and Risks

- Depends on FE-004 and backend 009-012 generated OpenAPI.
- RISK: report list/version and no-report terminal response shapes must be published.

## Decisions / Open Questions

- ARCHITECTURAL DECISION: backend is the sole authority for report ordering and
  scoring; the SPA is a read-only renderer in this increment.
- BLOCKER: requires approval and generated OpenAPI from backend 009-012.

## Definition of Ready

`BLOCKED` - FE-004 and approved generated OpenAPI from backend 009-012.
