# CV Matcher — Project Context

## Product purpose

CV Matcher is a web application that helps a recruitment team review CVs received in a shared Outlook Inbox. It analyzes candidates against vacancy requirements and produces explainable rankings. The system supports human decisions; it never automatically hires or rejects a person.

The authoritative functional source is `docs/PRD.md`. The delivery backlog is `docs/PRODUCT_BACKLOG.md`.

## Users and access

- `RECRUITER`: creates and manages shared vacancies, requests reports, reviews candidates, downloads protected CVs, exports reports, and maintains shared candidate profiles.
- `ADMIN`: has recruiter capabilities plus user/role management, system settings, Outlook and Claude integration management, privacy deletion, and audit access.

All recruiter data is shared. Audit records are immutable and visible only to administrators.

## Core workflow

1. A verified recruiter signs in using the product's own account system.
2. The recruiter creates a vacancy with title, description, a Bolivia-time date range, and weighted mandatory/optional requirements.
3. A durable asynchronous job obtains Inbox emails in the range from the shared Outlook account and identifies PDF or DOCX CVs.
4. CV text can be in Spanish or English. Claude returns per-requirement analysis in Spanish.
5. The backend validates the response and deterministically calculates the score and ranking.
6. The recruiter receives in-app and email notification when the report finishes or fails, then reviews, downloads, filters, exports, and applies a human operational status to candidates.

## Scoring rules

- Every requirement has a weight from 1 to 5.
- `mandatoryScore` is the weighted average of mandatory requirement compatibility.
- Optional requirements add a weighted bonus of up to 20 points.
- `totalScore` is capped at 100.
- Missing evidence for a mandatory requirement scores 0, but the candidate remains in the ranking and is marked `NO_DEMOSTRADO`.
- Ties are resolved by mandatory score, number of mandatory requirements met, then most recent CV.
- Claude provides compatibility, status, explanation, and evidence only. It cannot calculate the final score, rank, change requirements, hire, or reject.

## Candidate and document lifecycle

- A CV may be used in multiple vacancy reports.
- Each report displays one entry per duplicated person and uses the most recent CV; identity preference is CV email, then sender email, then normalized name.
- Original CVs are private local files; the database holds references and metadata.
- Recruiters can move CVs to a shared trash and restore them. Trash entries are permanently removed after 180 days and are excluded from all searches and reports.
- An administrator can perform immediate privacy deletion. Historical report entries are anonymized.
- Historical candidate search is offered only after the recruiter confirms it when a vacancy has no candidates meeting its threshold.

## Important product boundaries

- The initial document formats are PDF and DOCX only.
- UI, reports, notifications, and AI output are Spanish in version 1.
- Authentication is mandatory for protected operations; CVs never use public permanent links.
- Integration secrets, access tokens, CV contents, and personal data must never be exposed in logs, API errors, source control, or the client.
- A vacancy can have only one report job running at a time. Global job concurrency is configurable.

## Delivery guidance

Use the role workflow in `.agents/workflow.md`. Before implementation, turn approved PRD requirements into a scoped specification with acceptance criteria. Keep changes small, tested, reviewed for quality and security, and committed atomically in English using Conventional Commits.
