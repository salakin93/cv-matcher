# Spec 002B-4 - Retention, observability and job status

**Status:** Approved for implementation
**Parent:** [Spec 002B](002b-microsoft-graph-document-ingestion.md)
**Depends on:** 002B-3

## Objective

Expose safe job progress and enforce the approved 90-day lifecycle for stored
CV material, without exposing personal data or operational secrets.

## Scope

- Complete the job status/read model with state, timestamps, immutable job and
  retry links, counters, safe warning/error codes and correlation ID. Do not
  include candidate content, document text, file names, tokens or Graph
  responses.
- Maintain counters transactionally and monotonically across discovery,
  document outcomes, warnings and failures. Status reads must remain correct
  after restart and retry.
- Add structured operational logging and metrics using safe dimensions only:
  job state, outcome code, counter totals and correlation ID.
- Run daily retention for material whose last relevant completed job is older
  than 90 days. Delete encrypted original and extracted text, retain the
  minimum audit metadata, and create `RETENTION_EXPIRED` without personal
  data. The operation must be idempotent and recoverable if interrupted.
- Define a clear status outcome for completed jobs with ignored documents or
  truncated discovery (`READY_WITH_WARNINGS`) versus unrecoverable ingestion
  failure (`INGESTION_FAILED`).

## Out of scope

- Claude scoring and final candidate report (002C).
- Real-time push delivery to a frontend. The frontend may poll the status
  endpoint; a future increment can add SSE/WebSocket notifications.

## Acceptance criteria

1. An authorized caller can poll a safe, consistent status/counter response
   throughout all 002B states.
2. Repeated or interrupted retention does not remove audit trace or affect
   newer/shared deduplicated content.
3. Tests cover status serialization, counter updates, warning outcomes,
   90-day boundary, idempotent retention and safe logs.

## Required validation

```powershell
cd cv-matcher-backend
.\gradlew.bat test --tests "*Retention*" --tests "*Status*" --tests "*Counter*"
.\gradlew.bat test
git diff --check
```

## Review gate

Technical review is limited to retention, observability, status/counters and
their interaction with previously accepted 002B increments.
