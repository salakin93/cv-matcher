# Spec 002B-5 - Integrated end-to-end verification

**Status:** Approved for implementation
**Parent:** [Spec 002B](002b-microsoft-graph-document-ingestion.md)
**Depends on:** 002B-1, 002B-2, 002B-3 and 002B-4

## Objective

Prove the complete approved 002B flow works as one durable and secure system
before requesting the complete technical, QA and security reviews.

## Scope

- Add integration tests with PostgreSQL/Testcontainers for migrations,
  transactional dispatch, recovery and retained state.
- Use simulated Graph and ClamAV only. Tests must never require real Microsoft
  credentials, real candidate documents or outbound production calls.
- Exercise: OAuth refresh/rotation; paginated Inbox discovery; limits and
  truncation; durable restart; retry after failure and after reconnection;
  document validation; ClamAV outcome; AES-GCM storage; SHA-256 deduplication;
  PDF/DOCX extraction; counters/status; and 90-day retention.
- Verify the public error/status contract remains normalized and logs contain
  no tokens, CV content or other personal data.
- Fix integration defects found by this suite. No feature expansion beyond
  parent 002B is allowed in this increment.

## Required validation

```powershell
cd cv-matcher-backend
.\gradlew.bat cleanTest test
docker compose --env-file .env.example -f ..\compose.yaml config --quiet
docker compose --env-file .env.example -f ..\compose.yaml -f ..\compose.dev.yaml config --quiet
git diff --check
```

## Final review gate

Only after all acceptance criteria from 002B-1 through 002B-5 pass, request a
complete technical review of 002B. Resolve its findings, then request QA and
security/privacy review. A reviewer must identify the child specification
affected by each finding; a later-scope finding is not a rejection of an
already accepted child increment.
