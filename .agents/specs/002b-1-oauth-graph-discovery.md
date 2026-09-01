# Spec 002B-1 - OAuth refresh and Graph discovery

**Status:** Approved for implementation
**Parent:** [Spec 002B](002b-microsoft-graph-document-ingestion.md)
**Depends on:** Spec 002A

## Objective

Provide a server-side Microsoft connection and a bounded, deterministic Inbox
discovery client. This increment discovers messages and attachments only; it
does not persist CV files, run ClamAV, extract text, or execute a job worker.

## Scope

- Persist the Microsoft connection and encrypted refresh token server-side.
- Refresh access tokens on demand. Persist a rotated refresh token atomically
  when Microsoft returns one.
- If refresh is rejected because consent was revoked, the token expired, or the
  client secret is invalid, mark the connection unavailable and expose the
  domain condition `REAUTHORIZATION_REQUIRED`. Do not retry that condition.
- Implement a Graph client for `Inbox` using delegated access and
  `Prefer: IdType="ImmutableId"` on every request.
- Query messages whose `receivedDateTime` is inclusively inside the job's
  persisted UTC `from` and `to` range. The request must not select subject,
  sender, body, preview, or other unnecessary personal data.
- Page messages and attachment metadata/content until Graph provides no next
  link. Preserve only immutable message ID, receipt time, attachment ID, file
  name, MIME type, inline flag, and bytes needed by later increments.
- Apply limits before accepting a result: 500 messages, 1,000 attachments and
  1 GB aggregate attachment bytes. Return a typed `truncated` outcome with
  safe counters; never silently continue beyond a limit.
- Treat HTTP 429 using a valid `Retry-After`; retry it and transient timeouts,
  connection failures, and 5xx responses at most three times. The client must
  have connect/read timeouts and must not log tokens or response bodies.

## Out of scope

- Job dispatching, durable checkpoints, database message rows, recovery, file
  storage, malware scanning, extraction, retention, or public status changes.
- Any live Graph call in tests or application startup.

## API and design contract

- Keep Graph transport behind an interface so tests use a fake or stubbed HTTP
  server; no application service may depend directly on `HttpClient`.
- The discovery result must explicitly contain messages, accepted counters and
  `truncated`; a raw `List` is insufficient because 002B-2 must record a
  warning safely.
- OAuth configuration values and secret material come only from environment or
  external configuration. Refresh tokens remain encrypted at rest and are
  never returned by an endpoint.
- All times are `Instant`/UTC. Graph next links are treated as opaque URLs.

## Acceptance criteria

1. An expired access token refreshes without user interaction and rotated
   refresh tokens are retained encrypted.
2. A revoked/invalid connection maps to the domain reauthorization state,
   without leaking Microsoft response content.
3. Multi-page Inbox and multi-page attachments are completely traversed until a
   configured limit is reached.
4. The immutable-ID header and minimal `$select` properties are asserted by
   tests.
5. Tests cover inclusive UTC filters, normal pagination, 429 retry,
   transient retry exhaustion, reauthorization, each limit and truncation.

## Required validation

```powershell
cd cv-matcher-backend
.\gradlew.bat test --tests "*Microsoft*" --tests "*Graph*"
.\gradlew.bat test
git diff --check
```

## Review gate

Technical review is limited to this document and the corresponding parent
requirements. Findings must be labelled `002B-1`; they cannot request worker,
document, retention, or end-to-end work from later increments.
