# CV Matcher — Agent Instructions

This repository uses the delivery process and role definitions in `.agents/`.
Read the following files before planning, implementing, reviewing, or releasing
an increment:

1. `.agents/workflow.md` — delivery lifecycle, review gates, handoff format, and
   commit policy.
2. `.agents/context/project.md` — product context, users, workflow, and scoring
   rules.
3. `.agents/context/constraints.md` — mandatory product, privacy, security, and
   engineering constraints.
4. `.agents/roles/<role>.md` — responsibilities for the role being performed.
5. `.agents/specs/<increment>.md` — the approved scope and acceptance criteria
   for the increment being worked on.

## Operating rules

- Treat the approved spec as the implementation and review boundary. Do not add
  future or adjacent functionality.
- If authoritative sources conflict or a decision is missing, stop and request
  an explicit decision instead of guessing.
- Backend work uses Java 25. Preserve Flyway migration history and use
  PostgreSQL/Testcontainers where the applicable spec requires it.
- Never commit, log, expose, or use real credentials, tokens, CVs, personal
  data, provider payloads, or production integrations.
- Follow the workflow gates: implementation, technical review, QA review,
  security/privacy review, release review, then atomic commit.
- Use English Conventional Commits with a meaningful title and body. Before a
  commit, run the relevant validation and `git diff --check`.

## Repository layout

- `cv-matcher-backend/`: Spring Boot backend.
- `.agents/`: specifications, roles, product context, and delivery workflow.
- `docs/`: product, architecture, and frontend planning documents.
