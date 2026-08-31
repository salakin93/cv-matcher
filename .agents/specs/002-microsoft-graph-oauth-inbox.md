# Spec 002 - Microsoft Graph OAuth and Inbox PDF ingestion

## Status

Ready for backend implementation.

## Objective and scope

Connect one owner-controlled personal Outlook Inbox through Microsoft Graph delegated OAuth 2.0. Retrieve PDF CV attachments for an explicit date range, scan them with ClamAV, extract their text, and retain encrypted document artifacts with an auditable result.

Out of scope: mailbox rules/folders, DOCX, candidate matching, AI, moving messages, and frontend screens.

## Approved configuration

- The Inbox is exclusively for CVs.
- OAuth uses authorization code with PKCE. The application never uses the mailbox username/password.
- Microsoft authority: `consumers`.
- Local callback: `http://localhost:8080/oauth2/callback/microsoft`.
- Delegated permissions: `User.Read`, `Mail.Read`, `offline_access` only.
- Every manual ingestion requires inclusive `from` and `to` ISO-8601 UTC parameters; the maximum range is 31 days.

## Secrets and administrator access

The following are secrets and must be present only in ignored `.env` for local development and a secret manager in production:

```text
MICROSOFT_CLIENT_ID
MICROSOFT_CLIENT_SECRET
OAUTH_TOKEN_ENCRYPTION_KEY
CV_DOCUMENT_ENCRYPTION_KEY
ADMIN_API_TOKEN
```

- Access tokens are memory-only. Refresh tokens are encrypted at rest with AES-GCM and `OAUTH_TOKEN_ENCRYPTION_KEY`.
- Original PDF and extracted text are independently AES-GCM encrypted in a private named Docker volume mounted only by backend. `CV_DOCUMENT_ENCRYPTION_KEY` is a distinct Base64 32-byte key.
- Until user/role authentication exists, every operator endpoint requires `X-Admin-Token`, whose value is `ADMIN_API_TOKEN`. Compare it in constant time; never log or return it. Outside local development it is accepted only over TLS.
- Never log, expose, or commit mailbox passwords, authorization codes, tokens, addresses, subjects, bodies, filenames, CV text, or document bytes.

## Connection flow

1. An operator with `X-Admin-Token` starts a connection.
2. Backend persists a short-lived, single-use authorization attempt with cryptographically secure state, PKCE verifier/challenge, expiry, and scopes.
3. Backend redirects to Microsoft `consumers`; the owner signs in and consents.
4. Public callback validates and consumes state, exchanges the code server-side, calls `/me`, encrypts the refresh token, and records one active mailbox connection.
5. Browser receives only a generic success or controlled failure response.

## Ingestion flow

1. Operator starts `POST` ingestion with `from` and `to`; scheduler uses a persisted rolling UTC watermark.
2. Backend obtains a short-lived access token, queries Inbox messages whose `receivedDateTime` is in the inclusive range, and pages results.
3. It considers only non-inline PDF file attachments smaller than or equal to 10 MB.
4. A completed Graph message and attachment ID is never processed twice.
5. Each eligible PDF is scanned by internal ClamAV before text extraction.
6. A clean PDF is encrypted and stored; its extracted text is encrypted and stored separately. Database retains only protected references and safe metadata.
7. Unsupported, empty, oversized, infected, unscannable, or text-unextractable files are skipped. Summary counters expose only safe reason codes.

## Safe statuses

`DISCOVERED`, `PENDING_SECURITY_SCAN`, `STORED`, `TEXT_EXTRACTED`, `IGNORED`, `FAILED`.

Safe ignored reasons: `UNSUPPORTED_FORMAT`, `EMPTY_FILE`, `OVERSIZED`, `MALWARE_DETECTED`, `MALWARE_SCAN_FAILED`, `TEXT_EXTRACTION_FAILED`.

## Persistence and Flyway

| Record | Minimum fields | Constraints |
|---|---|---|
| OAuth connection | Microsoft account immutable ID, encrypted refresh token, IV/key version, scopes, timestamps, status | One active connection; no plaintext token. |
| Authorization attempt | hashed state, protected PKCE verifier, expiry, consumed time | Single-use and short-lived. |
| Ingestion run | correlation ID, trigger, range, timestamps, counters, status, safe error category | No mail content. |
| Inbox message | Graph message ID, received time, status, run reference, attempts | Unique ID; no subject or addresses. |
| CV document | Graph attachment ID, encrypted PDF/text references, SHA-256, MIME type, size, status, safe reason | Unique message + attachment ID. |

## API contract

| Operation | Method | Protection | Result |
|---|---|---|---|
| Start Microsoft connection | GET | `X-Admin-Token` | Redirect to Microsoft. |
| OAuth callback | GET | Public, state and PKCE protected | Generic completion response. |
| Read Inbox range | POST | `X-Admin-Token` | Requires `from`/`to`; `200 OK` safe run summary. |
| Latest status | GET | `X-Admin-Token` | `200 OK` safe counters/status. |

Use existing normalized JSON errors and safe categories: `MICROSOFT_AUTHORIZATION_FAILED`, `MICROSOFT_TOKEN_REFRESH_FAILED`, `MICROSOFT_GRAPH_UNAVAILABLE`.

## Compose and scheduler

- Add ClamAV as an internal Compose service with no host-published port.
- Scheduler is disabled in `local` and `test`, requires a successful connection, defaults to every 15 minutes in production, and is mutually exclusive.
- A failed run has bounded retries and cannot prevent later runs.

## Acceptance criteria

1. Owner completes delegated consent with exactly the three configured permissions.
2. `X-Admin-Token` protects all operator endpoints, never appears in logs/errors, and is not accepted over non-TLS production traffic.
3. Invalid, expired, or reused callback state is rejected without sensitive detail.
4. Valid UTC ranges up to 31 days page Inbox messages and discover only eligible PDFs without duplicate processing.
5. PDFs are scanned before extraction; clean original files and text are encrypted at rest.
6. Skipped files appear in the run summary only through safe reason codes.
7. Tokens, mailbox data, CV content, and filenames do not appear in source, tests, logs, metrics, APIs, or Git.
8. Graph/token failures do not halt later scheduled runs.
9. Tests use stubs only, with no real Microsoft credentials, mailbox data, email addresses, or CV files.

## Production follow-ups

1. Replace `X-Admin-Token` with application users and administrator roles before multi-user use.
2. Store all five secrets in a production secret manager.
3. Register the final HTTPS callback URL in Microsoft Entra.

## Validation

Implementation must pass Gradle tests, both Compose configuration validations, and `git diff --check`.
# Approved amendments - 2026-08-31

This section supersedes any conflicting requirement in the remainder of this specification.

## Supported document formats

- Accepted CV formats are PDF and DOCX, with a maximum of 10 MB per file.
- A clean PDF uses the PDF text extractor; a clean DOCX uses the DOCX text extractor.
- OCR is out of scope. A PDF without a text layer, a corrupt document, or a password-protected PDF/DOCX is `IGNORED`.
- Safe reasons include `UNSUPPORTED_FORMAT`, `EMPTY_FILE`, `OVERSIZED`, `MALWARE_DETECTED`, `MALWARE_SCAN_FAILED`, `PASSWORD_PROTECTED`, and `TEXT_EXTRACTION_FAILED`.

## Storage, scanning, and retention

- ClamAV is an internal Docker Compose service with no host-published port. Every accepted document is scanned before extraction.
- The original PDF/DOCX and its extracted text are separately AES-GCM encrypted in a private backend-only named Docker volume. `CV_DOCUMENT_ENCRYPTION_KEY` is a distinct secret.
- Original files and extracted text are retained for 90 days.
- A scheduled, auditable retention task deletes encrypted artifacts and document records after expiry. It retains only a minimal non-personal audit event with deletion timestamp, document SHA-256, and `RETENTION_EXPIRED` reason.

## Duplicate handling

- SHA-256 is the document-content identity.
- If a previously stored SHA-256 is received again, the new email attachment is recorded as a duplicate reference but its file and text are not stored or extracted again.
- Duplicate email traceability is preserved without duplicating sensitive content.

## Operator protection and date range

- Operator endpoints require the temporary `X-Admin-Token` header, using the `ADMIN_API_TOKEN` secret and constant-time comparison. It is allowed only over TLS outside local development.
- Manual ingestion requires inclusive `from` and `to` ISO-8601 UTC parameters; the maximum range is 31 days.
- The Inbox is dedicated exclusively to CVs.
# Demand-driven vacancy ingestion - approved decision

This section supersedes every scheduler, watermark, periodic-ingestion, or standalone Inbox-run reference in this specification.

- The application does not continuously monitor the Inbox and does not include a scheduler for this feature.
- Inbox ingestion happens only on demand when an operator creates a vacancy.
- Vacancy creation supplies the inclusive `from` and `to` UTC date-time range that determines the messages to retrieve for that vacancy. The maximum range remains 31 days.
- The ingestion run belongs to the created vacancy and its safe summary is associated with that vacancy.
- Only documents discovered in the chosen range are part of that vacancy's working set.
- A later vacancy may request another range. SHA-256 deduplication still avoids storing or extracting the same document twice while preserving the document-to-vacancy association.
- The implementation must use an asynchronous vacancy-ingestion job because scanning and extraction can exceed a synchronous HTTP request. Vacancy creation returns `202 Accepted` with a safe job summary and status location.
# Vacancy ingestion lifecycle - approved decisions

This section supersedes any conflicting vacancy, extraction, retry, duplicate-retention, or recovery statement in this specification.

## Vacancy states

- A vacancy is created as `PENDING_CV_INGESTION` and immediately starts its asynchronous Inbox-ingestion job.
- It becomes `READY` when the job completes without ignored documents.
- It becomes `READY_WITH_WARNINGS` when the job completes but one or more documents are ignored.
- It becomes `INGESTION_FAILED` when the job cannot complete because of a technical failure.
- Matching is not available until the vacancy is `READY` or `READY_WITH_WARNINGS`.

## Extraction definition

- This increment extracts normalized plain text only from clean PDF and DOCX documents.
- Candidate structured data, such as name, experience, skills, education, and matching against vacancy requirements, are outside this increment.
- Extraction succeeds only when normalized text contains at least 50 characters. Otherwise the document is `IGNORED` with `TEXT_EXTRACTION_FAILED`.

## Retry and recovery

- Retry up to three times only for transient Microsoft Graph, network, ClamAV, or protected-storage failures.
- Do not retry corrupt, password-protected, unsupported, oversized, infected, or text-unextractable files.
- An administrator may relaunch an `INGESTION_FAILED` vacancy using its same stored date range. The relaunch creates a new auditable job and preserves previous job history.

## Duplicate retention

- A SHA-256 duplicate keeps a reference from every vacancy that uses it.
- The encrypted document and extracted text remain until 90 days after the last active vacancy reference expires or is removed.
- Deletion removes the artifacts and document record only when no active reference remains, while retaining the minimal `RETENTION_EXPIRED` audit event.
# LLM scoring - approved decision

This section supersedes any conflicting future scoring instruction for this flow.

- Anthropic Claude Sonnet 5 evaluates each CV after malware scanning and text extraction. It receives only the extracted text, vacancy title/description, and requirements; it never receives the original email or document bytes.
- For each requirement, the LLM returns an integer `compatibilityScore` from 0 to 100. Missing or insufficient CV evidence requires a score of 0.
- `weight` is a required integer from 1 to 5. Every vacancy requires at least one `mandatory` requirement.
- Backend, not the LLM, calculates the deterministic final score:

```text
mandatoryScore = sum(weight * compatibilityScore for mandatory requirements)
                 / sum(weight for mandatory requirements)

optionalBonus = 20 * (sum(weight * compatibilityScore for optional requirements)
                 / sum(weight for optional requirements)) / 100

totalScore = min(100, mandatoryScore + optionalBonus)
```

- If the vacancy has no optional requirements, `optionalBonus` is 0.
- Optional requirements can add at most 20 points and can never reduce the score.
- The LLM response is validated against a strict JSON schema before server-side scoring. Invalid, incomplete, or unavailable LLM results are handled as safe per-document analysis failures and never produce invented scores.
# Claude response, job report, and concurrency - approved decisions

This section supersedes any conflicting response, report, or concurrency instruction in this specification.

## Claude response contract

Claude returns strict JSON for one CV at a time. The backend validates the response before persisting analysis or calculating scores.

```json
{
  "requirements": [
    {
      "requirementIndex": 0,
      "compatibilityScore": 85,
      "explanation": "Short compatibility explanation.",
      "evidence": "Short supporting evidence from the extracted text."
    }
  ],
  "summary": "Short candidate compatibility summary."
}
```

- The array contains exactly one result for each input requirement.
- `requirementIndex` maps to the zero-based input-requirement position.
- `compatibilityScore` is an integer from 0 through 100.
- Missing evidence requires score 0 and empty evidence.
- `explanation`, `evidence`, and `summary` are user-facing analysis data, never log data.
- The backend calculates `mandatoryScore`, `optionalBonus`, and `totalScore`; Claude never supplies those fields.
- An invalid response receives one corrective retry. If it remains invalid, the document is `ANALYSIS_FAILED` and does not receive an invented score.

## Final job report

The completed job status response contains a safe aggregate summary and candidates sorted by `totalScore` descending, then `mandatoryScore` descending.

```json
{
  "jobId": "uuid",
  "status": "COMPLETED_WITH_WARNINGS",
  "summary": {
    "processedMessages": 15,
    "acceptedDocuments": 10,
    "ignoredDocuments": 2,
    "duplicateDocuments": 3,
    "analyzedCandidates": 9,
    "analysisFailures": 1
  },
  "candidates": [
    {
      "rank": 1,
      "documentId": "uuid",
      "mandatoryScore": 82.5,
      "optionalBonus": 11.4,
      "totalScore": 93.9,
      "requirements": [],
      "summary": "Short candidate compatibility summary."
    }
  ]
}
```

- `documentId` is the safe candidate identifier for this increment; candidate name and structured profile extraction are deferred.
- Candidate analysis content is visible only in the protected job-status response and is retained for the same 90-day lifecycle as the document.

## Single active job

- Only one matching job may be active globally in `QUEUED`, `INGESTING_EMAILS`, `SCANNING_DOCUMENTS`, `EXTRACTING_TEXT`, or `ANALYZING_CANDIDATES` status.
- The restriction is enforced with a database-backed constraint or lock, never in-memory only.
- A request received while a job is active responds with `409 Conflict`, its `activeJobId`, and its protected `statusUrl`.
- Once a job reaches a terminal status, a new vacancy job may be created.
# Final API, LLM presentation, retention, and limits - approved decisions

This section supersedes any conflicting API, LLM presentation, retention, limit, or retry instruction in this specification.

## API routes

- `POST /api/matching-jobs` receives the approved vacancy request body (`title`, `description`, `requirements`, `from`, `to`) and responds `202 Accepted` with `jobId`, initial status, and protected status URL.
- `GET /api/matching-jobs/{jobId}` returns current job progress or its completed report. The frontend polls this endpoint every 3 to 5 seconds until a terminal status.
- `POST /api/matching-jobs/{jobId}/retry` relaunches only an `INGESTION_FAILED` job using its original vacancy body and date range. It creates a new auditable job while preserving the original job history.

## LLM presentation contract

- Claude's `summary`, per-requirement `explanation`, and `evidence` are Spanish user-facing strings with a maximum of 300 characters each.
- They must rely only on evidence present in the extracted CV text; Claude must not infer, invent, or add facts.
- The protected completed-job report exposes the validated per-requirement result for every analyzed document:

```json
{
  "requirementIndex": 0,
  "compatibilityScore": 85,
  "explanation": "Short Spanish explanation.",
  "evidence": "Short Spanish evidence from the extracted CV text."
}
```

## Retention

- A completed job, its report, protected document artifacts, and extracted text are retained for 90 days after the job reaches a terminal status.
- If a SHA-256 document is referenced by more than one job, its encrypted artifacts remain until 90 days after the most recently completed referencing job.
- Expiry deletes protected artifacts, extracted text, document records, and job report. The system retains only the minimal non-personal `RETENTION_EXPIRED` audit event.

## Limits and retries

- One job processes at most 500 messages and 1,000 attachments. Reaching either limit produces `COMPLETED_WITH_WARNINGS` and a safe truncation warning in the summary.
- Microsoft Graph, network, ClamAV, and protected-storage transient failures retry at most three times with bounded backoff.
- Anthropic transport/transient failures retry at most two times. A syntactically invalid Claude JSON response receives one corrective retry; a still-invalid response becomes `ANALYSIS_FAILED` and receives no invented score.
# Superseded specification

This original combined specification is retained as a historical reference only. Its implementation is replaced by:

- `002a-vacancy-job-foundation.md`
- `002b-microsoft-graph-document-ingestion.md`
- `002c-claude-analysis-final-report.md`
