# CV Matcher — Mandatory Constraints

These constraints apply to every role and every delivery. If a requirement conflicts with this file, stop and request an explicit product or architecture decision before changing the behaviour.

## Product and decision boundaries

- CV Matcher assists recruiters; it never automatically hires, rejects, or changes a human operational decision.
- Claude may evaluate evidence for each requirement only. The backend validates its structured response and is solely responsible for calculating scores, rankings, and tie-breaking.
- A candidate with no evidence for a mandatory requirement receives zero for that requirement and remains visible in the ranking as `NO_DEMOSTRADO`.
- Optional requirements use their configured weights and can add no more than 20 points to the final score.
- Human candidate status belongs to a candidate–report relationship and must not affect other vacancies or historical reports.
- Changes to a vacancy create a new immutable report version; existing reports must remain reproducible.

## Language and content

- The version 1 user interface, notifications, exports, report text, and AI output are Spanish.
- CV source documents may be Spanish or English and must be processable in either language.
- The initial supported CV formats are PDF and DOCX only. Unsupported, corrupt, password-protected, or non-CV documents must be safely ignored and reported without sensitive content.

## Authorization and privacy

- Authentication is mandatory for every protected frontend and backend operation. Missing or invalid credentials return `401 Unauthorized`.
- Only administrators can manage users, roles, system configuration, integrations, privacy deletion, and audit records.
- Recruiters work with shared vacancy, report, and candidate-directory data.
- CV files, extracted text, personal data, access tokens, refresh tokens, client secrets, encryption keys, and provider responses must never be committed, logged, exposed in API errors, or rendered to unauthorized users.
- Original CV downloads require authenticated authorization. Public permanent file links are forbidden.
- A privacy deletion must remove the original file and processed personal data immediately, preserve only minimal non-personal audit information, and anonymize historical report entries.
- A trashed CV is excluded from every report, ranking, and historical search. It can be restored for 180 days, after which it is permanently removed.

## Integrations and jobs

- Outlook is a shared system integration. Jobs read only the Inbox and only within the vacancy's persisted date range.
- Date ranges are entered in `America/La_Paz` and persisted/queried in UTC.
- Integration credentials and tokens are server-side only; UI screens may show connection health but never secrets.
- Report generation is asynchronous and durable. A HTTP request must not wait for document ingestion or AI analysis.
- A vacancy may have at most one active report job. Global job concurrency is configurable.
- Partial processing failures produce safe warnings and preserve successful results. No sensitive document or provider payload may appear in failure details.

## Engineering and delivery

- Use Java 25 for backend development. Do not lower the Java baseline without explicit approval.
- Follow the role sequence defined in `.agents/workflow.md`: approved requirements/specification, implementation, technical review, QA, security/privacy review, then release review.
- Scope implementation to the approved specification. Do not add adjacent features merely because they seem useful.
- Add proportionate automated tests for new behaviour. Do not use real credentials, real CVs, or production integrations in tests.
- Keep commits atomic, written in English, and formatted as Conventional Commits with a meaningful title and description.
- Do not alter approved PRD decisions without updating the PRD and obtaining explicit approval.
