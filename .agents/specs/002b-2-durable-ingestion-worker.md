# Spec 002B-2 - Durable ingestion worker, states and recovery

**Status:** Approved for implementation
**Parent:** [Spec 002B](002b-microsoft-graph-document-ingestion.md)
**Depends on:** 002B-1

## Objective

Turn a created or retried matching job into a durable background execution.
The worker calls 002B-1 discovery, records progress durably, and can resume
safely after an application restart.

## Scope

- Dispatch a job only after the transaction creating it commits.
- Claim work safely in PostgreSQL so two application instances cannot process
  the same job. The existing single-active-job rule remains authoritative.
- Persist state transitions, progress counters, discovery checkpoints and safe
  warnings. A restart resumes queued or interrupted non-terminal work from the
  last durable checkpoint; it never starts a periodic Inbox scan.
- Drive the defined lifecycle: `QUEUED` → `INGESTING_EMAILS` →
  `SCANNING_DOCUMENTS`. 002B-3 owns document completion states.
- Map a connection loss from 002B-1 to `REAUTHORIZATION_REQUIRED`; map a
  bounded operational failure to `INGESTION_FAILED`. Preserve an auditable,
  safe reason and do not expose provider payloads.
- Store discovered message identity and attachment identity idempotently, using
  immutable Graph IDs. Rerunning/resuming must not create duplicate rows or
  increment counters twice.
- Implement retry creation according to 002A/002B: terminal jobs are immutable;
  a retry creates a linked, auditable new FULL job. It permits
  `INGESTION_FAILED`, and `REAUTHORIZATION_REQUIRED` only after a successful
  Microsoft reconnection.

## Out of scope

- Malware scanning, encryption, hash deduplication, parsing, retention and
  final report generation.

## Acceptance criteria

1. The HTTP request returns promptly after commit; work is not executed before
   its job transaction commits.
2. A process restart recovers every eligible non-terminal job exactly once.
3. Duplicate dispatch, page replay and retry cannot duplicate message records
   or counters.
4. State transition failures, reauthorization, transient failure and restart
   recovery have deterministic tests.
5. A truncated 002B-1 result records `RANGE_TRUNCATED` as a warning and moves
   deterministically to the next state.

## Required validation

```powershell
cd cv-matcher-backend
.\gradlew.bat test --tests "*Worker*" --tests "*Recovery*" --tests "*MatchingJob*"
.\gradlew.bat test
git diff --check
```

## Review gate

Technical review is limited to durable dispatch, persistence, state handling,
recovery and their tests. Findings about secure document processing belong to
002B-3 or later.
