# Project status and handoff

Last stable commit: `8022de1 refactor(ingestion): isolate graph calls from transactions`

## Completed and committed

- Project foundations: configuration, security, Docker, Flyway, and profiles.
- Spec 002A: matching-job creation, status lookup, and retry.
- Spec 002B-1: server-side Microsoft OAuth, refresh tokens, paginated Inbox
  discovery, immutable IDs, limits, and retries.
- TECH-001: asynchronous dispatch in a separate Spring bean.
- TECH-002 base: Graph discovery is orchestrated outside the main worker
  transaction through `IngestionJobPersistenceService` and immutable
  `JobSnapshot` values.

## Pending work

### Spec 002B-2 technical-review findings

- TECH-002-A: clear `ingestion_claimed_at` after successful processing and
  terminal errors.
- TECH-002-B: add a PostgreSQL/Testcontainers test proving a blocked Graph call
  does not keep database transactions or locks open.
- TECH-002-C: make message insertion idempotent under concurrent unique-key
  races.
- TECH-003: implement safe multi-instance claims using expiring leases.
- TECH-004: persist and consume ingestion checkpoints during recovery.
- TECH-005: add integrated worker, concurrency, and recovery coverage.
- TECH-006: support RFC 1123 date values in Graph `Retry-After` headers.

### Subsequent specifications

- 002B-3: document validation, ClamAV, AES-GCM storage, SHA-256
  deduplication, and PDF/DOCX extraction.
- 002B-4: retention, observability, counters, and status reporting.
- 002B-5: end-to-end verification of the complete 002B flow.
- 002C: Claude analysis and final report.
- QA, security/privacy, and release reviews after each applicable increment.

## Local setup note

The reset to the stable commit removed `.env`. Recreate it from `.env.example`
before running the application and provide local credentials through that file.

## Suggested restart point

Resume with TECH-002-A, TECH-002-B, and TECH-002-C as one bounded corrective
increment. Run worker/matching tests, the complete suite, and `git diff --check`
before requesting a technical review limited to TECH-002.
