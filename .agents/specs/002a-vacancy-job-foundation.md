# Spec 002A - Vacancy job foundation

## Status

Ready for backend implementation. This is the prerequisite for 002B and 002C.

## Objective

Create the persistent, protected asynchronous-job foundation that accepts one vacancy request, enforces one globally active job, and exposes safe status polling. This increment performs no Microsoft Graph, document, ClamAV, or Claude call.

## Request contract

`POST /api/matching-jobs` receives:

```json
{
  "title": "Inspector SIMA",
  "description": "Vacancy description.",
  "requirements": [
    {
      "description": "Requirement text.",
      "weight": 5,
      "mandatory": true
    }
  ],
  "from": "2026-08-01T00:00:00Z",
  "to": "2026-08-31T23:59:59Z"
}
```

Validation rules:

- `title` and `description` are non-blank.
- `requirements` is non-empty and contains at least one `mandatory: true` item.
- Every `weight` is an integer from 1 through 5.
- `from` and `to` are inclusive ISO-8601 UTC instants; `from` is not after `to`; range is at most 31 days.

## Security

- All operator endpoints require `X-Admin-Token` matching the secret `ADMIN_API_TOKEN` with constant-time comparison.
- The token is never logged, returned, committed, or accepted over non-TLS production traffic.
- Validation and error responses use the project normalized JSON error format and disclose no submitted vacancy content.

## Persistence and Flyway

Create migrations for:

| Record | Minimum fields |
|---|---|
| Matching job | UUID, title, description, UTC range, status, timestamps, retry-of ID, correlation ID, safe error category, counters |
| Job requirement | UUID, job ID, display order, description, weight, mandatory flag |
| Job event | UUID, job ID, timestamp, old/new status, safe event type and safe details |

The job and requirements are persisted transactionally before returning `202 Accepted`.

## Job lifecycle

Initial and shared statuses are `QUEUED`, `INGESTING_EMAILS`, `SCANNING_DOCUMENTS`, `EXTRACTING_TEXT`, `TEXT_EXTRACTION_COMPLETE`, `ANALYZING_CANDIDATES`, `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `INGESTION_FAILED`, and `REAUTHORIZATION_REQUIRED`.

This increment creates a job in `QUEUED` and provides the lifecycle storage/API. Processing transitions are added by later specs.

Only one job may globally be active in a non-terminal status. Enforce this with a database-backed constraint or lock, never memory only. If another request arrives, return `409 Conflict` with the active job ID and its status URL.

## API contract

| Operation | Behavior |
|---|---|
| `POST /api/matching-jobs` | Creates job and returns `202 Accepted`, `jobId`, `QUEUED`, and `statusUrl`. |
| `GET /api/matching-jobs/{jobId}` | Returns safe job status, safe counters, and no candidate analysis yet. |
| `POST /api/matching-jobs/{jobId}/retry` | Reserved for later; returns a normalized client error unless the job is eligible under 002B/002C. |

The frontend polls the status endpoint every 3 to 5 seconds. It stops at a terminal status.

## Acceptance criteria

1. Valid vacancy requests create durable `QUEUED` jobs and return `202 Accepted`.
2. Invalid requests return normalized 4xx errors.
3. A second concurrent creation receives `409 Conflict` and the existing job reference.
4. Status survives an application restart.
5. No Graph, document storage, ClamAV, Anthropic, credentials, or CV data is introduced.
6. Tests cover validation, token rejection, success, global concurrency, and persisted status.
# Approved implementation details

This section supersedes any conflicting lifecycle, API, validation, concurrency, or administrator-token statement in this specification.

## State transitions

- 002A creates jobs only in `QUEUED` status. It does not start processing or transition to `INGESTING_EMAILS`; 002B adds those transitions.
- Terminal statuses are `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `INGESTION_FAILED`, and `REAUTHORIZATION_REQUIRED`.
- A terminal job never becomes active again. A retry creates a new `QUEUED` job with `retryOfJobId` referencing its terminal predecessor.

## Exact API responses

- A successful `POST /api/matching-jobs` returns `202 Accepted`, a `Location` header containing the protected status URL, and:

```json
{
  "jobId": "uuid",
  "status": "QUEUED",
  "statusUrl": "/api/matching-jobs/uuid",
  "createdAt": "2026-08-31T15:00:00Z"
}
```

- `GET /api/matching-jobs/{jobId}` returns the fields above, persisted request range, safe zero-value counters, terminal status when applicable, and only safe error information.
- A missing or invalid `X-Admin-Token` returns normalized `401 Unauthorized`.
- A concurrent creation returns normalized `409 Conflict` with `activeJobId` and protected `statusUrl`.
- An unknown job returns normalized `404 Not Found`.

## Request limits

- Title: at most 200 characters.
- Description: at most 10,000 characters.
- Requirements: at most 50 items.
- Each requirement description: at most 2,000 characters.

## Database concurrency

- PostgreSQL enforces the single-active-job rule with a partial unique index covering non-terminal statuses.
- The application translates a unique-constraint race into the normalized `409 Conflict` response.
- In-memory locks may optimize execution but cannot be the source of correctness.
# Cross-spec job-mode and terminal-state decisions

This section supersedes any conflicting retry or terminal-state instruction across 002A, 002B, and 002C.

- Every job has `jobMode`: `FULL` or `ANALYSIS_ONLY`.
- `POST /api/matching-jobs` always creates a `FULL` job.
- Every retry creates a new `QUEUED` job with `retryOfJobId` and never reactivates its predecessor.
- `FULL` retries repeat Microsoft Graph, ClamAV, document storage, and extraction as defined by 002B.
- `ANALYSIS_ONLY` retries reuse protected extracted text and run only 002C analysis.
- `COMPLETED_WITH_WARNINGS` is terminal when the workflow successfully ran but zero documents were analyzable because all were safely ignored; its candidate list is empty.
- `INGESTION_FAILED` is terminal only when a technical failure prevents the requested flow from completing. `REAUTHORIZATION_REQUIRED` is terminal only when Microsoft authorization needs owner action.
# Final cross-spec state machine

This section supersedes any conflicting job-transition statement across 002A, 002B, and 002C.

```text
FULL:
QUEUED -> INGESTING_EMAILS -> SCANNING_DOCUMENTS -> EXTRACTING_TEXT
-> TEXT_EXTRACTION_COMPLETE -> ANALYZING_CANDIDATES
-> COMPLETED | COMPLETED_WITH_WARNINGS | INGESTION_FAILED | REAUTHORIZATION_REQUIRED

ANALYSIS_ONLY:
QUEUED -> ANALYZING_CANDIDATES
-> COMPLETED | COMPLETED_WITH_WARNINGS | INGESTION_FAILED
```

- Every transition is persisted transactionally before the next stage starts.
- Terminal statuses cannot transition further.

