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
