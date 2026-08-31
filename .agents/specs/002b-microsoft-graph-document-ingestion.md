# Spec 002B - Microsoft Graph and document ingestion

## Status

Ready after 002A is complete.

## Objective

Connect the owner-controlled personal Outlook Inbox with delegated Microsoft Graph OAuth and process the date-range documents belonging to a queued vacancy job. The increment scans PDF/DOCX files, extracts plain text, encrypts artifacts, and prepares documents for Claude analysis. It does not call Claude or calculate matching scores.

## Dependencies and scope

- Requires 002A persistent job, requirement, token protection, and status API.
- Inbox is exclusively for CVs; there is no scheduler or continuous polling.
- Ingestion starts only for the vacancy job's persisted `from`/`to` range.
- Microsoft authority is `consumers`; scopes are only `User.Read`, `Mail.Read`, and `offline_access`.
- Local callback is `http://localhost:8080/oauth2/callback/microsoft`.

## OAuth and secrets

- Use authorization code flow with PKCE and single-use expiring state.
- Access tokens are memory-only. Refresh tokens are AES-GCM encrypted using `OAUTH_TOKEN_ENCRYPTION_KEY`.
- Required secrets: `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, `OAUTH_TOKEN_ENCRYPTION_KEY`, and `CV_DOCUMENT_ENCRYPTION_KEY`.
- Never use or store the Outlook username/password.
- Invalid or revoked authorization marks the job `REAUTHORIZATION_REQUIRED` without automatic retry.

## Ingestion flow

1. A queued job transitions to `INGESTING_EMAILS`.
2. Query `/me/mailFolders/inbox/messages` only for messages received in the job's inclusive UTC range. Use immutable Graph identifiers and page the response.
3. Process at most 500 messages and 1,000 attachments. Hitting either cap leads to a safe truncation warning.
4. Accept only non-inline PDF and DOCX files at most 10 MB.
5. A message/attachment pair is idempotent. SHA-256 identifies equal document content across jobs.
6. Scan eligible bytes with internal ClamAV before extraction.
7. Clean PDF/DOCX files and their extracted normalized plain text are separately AES-GCM encrypted in a backend-only named Docker volume.
8. On successful extraction, document status becomes `TEXT_EXTRACTED`. When all documents are handled, job becomes `TEXT_EXTRACTION_COMPLETE`.

## Rejection and retries

- Ignore unsupported, empty, oversized, infected, unscannable, password-protected, corrupt, or text-unextractable files.
- Safe reasons: `UNSUPPORTED_FORMAT`, `EMPTY_FILE`, `OVERSIZED`, `MALWARE_DETECTED`, `MALWARE_SCAN_FAILED`, `PASSWORD_PROTECTED`, `TEXT_EXTRACTION_FAILED`.
- OCR is out of scope.
- Retry transient Graph, network, ClamAV, and protected-storage failures at most three times with bounded backoff. Do not retry rejected files.

## Persistence, Compose, and retention

Create Flyway migrations for encrypted OAuth connection, authorization attempt, message, document, and job-document-reference records. Persist no email subject, address, body, filename, plaintext token, document bytes, or extracted text in logs.

Add ClamAV as an internal Compose service with no host-published port. Document artifacts and final job reports are retained for 90 days after the most recently completed job reference, then deleted with a minimal `RETENTION_EXPIRED` audit event.

## API additions

- Protected endpoint to start Microsoft authorization.
- Public, state/PKCE-protected OAuth callback with generic completion response.
- `POST /api/matching-jobs/{jobId}/retry` creates a new auditable job only when the previous job is `INGESTION_FAILED` or `REAUTHORIZATION_REQUIRED` has been resolved.

## Acceptance criteria

1. Owner can connect the mailbox without exposing passwords, authorization codes, or tokens.
2. A 002A job reads only its requested Inbox range and does not run continuously.
3. PDF/DOCX files are scanned before extraction; clean files/text are encrypted at rest.
4. Duplicate content is not stored or extracted twice but remains associated with each job.
5. Safe ignored reasons and counters are visible through the job status.
6. Job reaches `TEXT_EXTRACTION_COMPLETE` without any Anthropic call.
7. Graph integration tests use stubs only and contain no real credentials, mailbox data, or CVs.
# Approved execution, OAuth, storage, and file-security details

This section supersedes any conflicting execution, OAuth, storage, validation, encryption, cleanup, or Graph instruction in this specification.

## Demand-driven execution and recovery

- Inbox ingestion remains demand-driven: only a vacancy job starts it. There is no periodic Inbox scheduler.
- On backend startup, an interrupted active job resumes automatically from its last persisted safe checkpoint and skips already completed message/attachment work.
- Recovery continues the same job and never creates another job. Database idempotency remains the source of correctness.

## OAuth endpoints and connection policy

- `GET /api/integrations/microsoft/authorize` requires `X-Admin-Token` and redirects to Microsoft authorization.
- `GET /oauth2/callback/microsoft` is public only for Microsoft callback and validates state plus PKCE.
- `GET /api/integrations/microsoft/connection` requires `X-Admin-Token` and returns safe connection status without mailbox address or token data.
- Authorization state expires after 10 minutes and is single-use.
- Only one Microsoft mailbox connection may be active.

## Storage and file limits

- A job reads at most 500 messages, 1,000 attachments, and 1 GB of aggregate attachment bytes.
- On reaching any limit, discovery stops, a safe `RANGE_TRUNCATED` warning is recorded, and accepted documents continue through extraction. The job carries the warning into its later final `COMPLETED_WITH_WARNINGS` result.
- Validate the actual document signature (magic bytes) in addition to extension and MIME type before storage or extraction.
- Enforce the 10 MB per-file limit before persistence.
- ClamAV scan timeout is 60 seconds; text extraction timeout is 30 seconds. Timeout results are safe ignored reasons.

## Encryption and retention cleanup

- Every encrypted original artifact and extracted-text artifact uses a new random AES-GCM nonce.
- Store nonce and encryption-key version only as protected metadata; never store the key with the artifact.
- A daily retention-cleanup task removes artifacts and records that exceeded their retention window. It does not query or read Inbox messages and is therefore not an Inbox scheduler.

## Microsoft Graph rules

- Request immutable message identifiers with `Prefer: IdType="ImmutableId"`.
- Query only the minimum required message properties: received date/time, immutable identifiers, attachment metadata, and pagination data. Do not request or persist subject or message body.
- On Graph `429`, honor `Retry-After` before using the approved bounded retry policy.
# Final worker, connection, extraction, and DOCX decisions

This section supersedes any conflicting worker, connection-precondition, extraction-success, or DOCX-security instruction in this specification.

## On-demand worker

- After 002A persists a matching job and commits its transaction, it triggers the asynchronous ingestion worker.
- The worker claims jobs from PostgreSQL, never from in-memory state only.
- On application startup, it claims recoverable `QUEUED` and interrupted active jobs using their persisted checkpoints.
- This worker executes requested jobs only; it does not periodically read or monitor Inbox.

## Microsoft connection precondition

- `POST /api/matching-jobs` verifies that one active Microsoft OAuth connection exists before persisting a job.
- If no active connection exists, it returns normalized `409 Conflict` with safe code `MICROSOFT_CONNECTION_REQUIRED` and the protected authorization-start URL.
- No job is created when this precondition fails, so no global active-job slot is consumed.

## Extraction success

- PDF and DOCX extracted text is normalized before evaluation.
- An extraction is successful only when normalized text contains at least 50 characters.
- A shorter result is `IGNORED` with `TEXT_EXTRACTION_FAILED` and is never sent to Claude.

## DOCX decompression protection

- In addition to the 10 MB compressed-file limit, DOCX uncompressed content is limited to 50 MB.
- A DOCX exceeding the decompressed-content limit is rejected before extraction with a safe ignored reason and is never stored, scanned further, or sent to Claude.
# Cross-spec result and retry decisions

This section supersedes any conflicting result or retry instruction in this specification.

- A `FULL` job with zero `TEXT_EXTRACTED` documents after successful Inbox processing ends as `COMPLETED_WITH_WARNINGS`, with safe ignored counters and no candidates. It does not proceed to 002C.
- A technical Graph, ClamAV, storage, or extraction failure that prevents the requested flow from completing ends as `INGESTION_FAILED` and may be retried only as a new `FULL` job.
- `REAUTHORIZATION_REQUIRED` remains terminal until the owner reconnects Microsoft; after a successful reconnection, retry creates a new `FULL` job.
# Automatic handoff to analysis

This section supersedes any conflicting completion instruction in this specification.

- A `FULL` job with one or more `TEXT_EXTRACTED` documents persists `TEXT_EXTRACTION_COMPLETE` and automatically starts 002C analysis through the same database-backed worker.
- No additional frontend request is required to start analysis.
- A `FULL` job with zero analyzable documents ends directly as `COMPLETED_WITH_WARNINGS` and never starts 002C.

