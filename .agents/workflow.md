# CV Matcher Delivery Workflow

This workflow applies to every product increment. Its purpose is to keep decisions traceable, implementation scoped, and reviews effective.

## Sources of truth

1. `docs/PRD.md` — approved functional requirements.
2. `docs/PRODUCT_BACKLOG.md` — approved product breakdown and delivery order.
3. `.agents/context/project.md` — concise product context.
4. `.agents/context/constraints.md` — mandatory boundaries.
5. `.agents/specs/<increment>.md` — approved, implementation-ready scope for one increment.
6. `docs/architecture.md` — approved technical decisions, once it exists.

If sources conflict, do not guess. Stop and request an explicit decision; then update the authoritative document before implementation continues.

## Standard lifecycle

```text
Product Requirements Analyst
  → PRD approval
  → Architect
  → scoped specification approval
  → Backend Developer and/or Frontend Developer
  → Technical Reviewer
  → developer resolves findings
  → Technical Reviewer rechecks the same scope
  → QA Reviewer
  → Security & Privacy Reviewer
  → Release Reviewer
  → atomic commit and release decision
```

Not every increment needs frontend work. Technical, QA, and security reviews may run in parallel only after the technical review has approved the scoped implementation.

## Temporary integrated manual-validation policy

Until the product reaches the explicitly planned final stabilization phase, individual increments use documented manual validation instead of creating or requiring new automated tests. Validation must exercise the implemented frontend/backend flow when applicable and record cases, outcome, environment, and defects resolved. Existing tests must not be removed, disabled, or made less strict to obtain approval.

The test strategies retained in each spec are the mandatory automation backlog for final stabilization. That phase must execute the accumulated unit, integration, API, E2E, security/privacy, migration, and regression coverage before a production release decision. `git diff --check`, review gates, privacy/security constraints, and safe manual verification remain required throughout.

## Responsibilities and gates

### 1. Product Requirements Analyst

- Elicits product decisions one at a time when needed.
- Maintains the PRD and backlog after approval.
- Defines user value and acceptance criteria, not technical implementation.
- No development starts from an unapproved or ambiguous product requirement.

### 2. Architect

- Converts approved PRD scope into a small specification.
- Records architecture decisions and identifies blockers before implementation.
- Each spec must state: goal, in-scope behaviour, explicit exclusions, API/data contracts when relevant, security/privacy requirements, acceptance criteria, a manual-validation plan, deferred automation backlog, and dependencies.
- Split broad work into ordered increments. Do not create a spec that requires unrelated future components to be complete.

### 3. Developers

- Implement only the approved spec and its acceptance criteria.
- Do not add adjacent features, speculative infrastructure, or later increments.
- Do not create or require new automated tests during the temporary integrated manual-validation phase. Execute and document the manual validation plan; preserve all existing tests and automation plans for final stabilization.
- Keep worktree changes focused. Make atomic English Conventional Commits only after the required reviews approve, unless an approved development checkpoint explicitly requests one.

### 4. Technical Reviewer

- Reviews only the named spec and explicitly listed commits/files.
- Evaluates design, maintainability, transactions, concurrency, migrations, error handling, documented manual-validation evidence, deferred automation coverage, and consistency with the approved architecture.
- Must not report missing functionality belonging to excluded or future increments.
- Reports `APROBADO` or `CAMBIOS_REQUERIDOS`, with severity, evidence, impact, recommendation, affected spec, and validation commands.

### 5. QA Reviewer

- Verifies implemented behaviour only against the named approved spec and applicable PRD acceptance criteria, using the documented manual-validation plan during the temporary phase.
- Does not modify code.
- Reports reproducible findings by severity and validation commands.

### 6. Security & Privacy Reviewer

- Reviews secrets, authorization, personal data/CVs, logs, exports, token handling, integrations, and AI use.
- Does not modify code.
- Reviews only the delivered increment while enforcing all mandatory privacy constraints.

### 7. Release Reviewer

- Confirms technical, QA, and security approval; manual-validation evidence during the temporary phase, or the full automated test results at final stabilization; migration and deployment readiness; documentation; and a clean diff.
- A release is blocked by unresolved critical or high-severity findings.

## Review and correction loop

1. A reviewer records only findings inside the agreed scope.
2. The developer resolves those findings without expanding scope.
3. The same reviewer rechecks the same spec and findings.
4. The increment advances only when the relevant gate approves it.
5. A newly discovered requirement outside scope becomes a backlog item or a new spec; it does not block the current increment unless it makes the delivered behaviour unsafe or incorrect.

## Commit policy

- Use English Conventional Commits, for example `feat(auth): add email verification`.
- Each commit must have a concise title and an English body explaining the meaningful change and validation performed.
- Do not mix unrelated work, generated files, credentials, or unreviewed changes.
- Before committing, execute the applicable documented manual validation and `git diff --check`. Automated test execution becomes mandatory again during final stabilization.

## Required handoff format

Every role handoff must name the role, the exact document/spec in scope, whether code changes are allowed, expected deliverable, and validation requested. For reviews, it must explicitly list excluded increments.
