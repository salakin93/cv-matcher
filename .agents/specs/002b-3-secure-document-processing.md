# Spec 002B-3 - Secure document processing

**Status:** Approved for implementation
**Parent:** [Spec 002B](002b-microsoft-graph-document-ingestion.md)
**Depends on:** 002B-2

## Objective

Process discovered attachments securely into encrypted, deduplicated document
records and extracted text suitable for the later Claude analysis increment.

## Scope

- Accept only PDF and DOCX attachments. Validate file size, MIME type,
  extension and file signature before processing.
- Send accepted bytes to ClamAV on the internal Docker network with a bounded
  timeout. Infected, unavailable after bounded retries, or invalid documents
  receive safe ignored/failure reasons and never reach text extraction.
- Calculate SHA-256 after validation. Preserve the new email/attachment
  reference for every delivery, but store a single encrypted binary and a
  single extraction for equal content hashes.
- Encrypt original bytes and extracted text with AES-GCM using
  `CV_DOCUMENT_ENCRYPTION_KEY`, a fresh nonce per ciphertext and an explicit
  key-version field. Do not persist plaintext files or plaintext extracted
  text.
- Extract PDF and DOCX text under time and size limits. OCR is excluded. PDFs
  without a usable text layer or corrupted PDFs become `IGNORED` with
  `TEXT_EXTRACTION_FAILED`; password-protected PDFs become `IGNORED` with
  `PASSWORD_PROTECTED`. Equivalent malformed DOCX cases use safe extraction
  failure reasons.
- Bound transient ClamAV/storage/extraction retries to three attempts and make
  each operation resumable/idempotent from durable document state.

## Security constraints

- Never log document bytes, extracted text, candidate details, OAuth tokens or
  encryption key material.
- Fail closed if the encryption key is absent, malformed or has the wrong
  length. Do not substitute a generated production key.
- ClamAV must not be publicly exposed by Compose.

## Acceptance criteria

1. Valid PDF and DOCX documents are scanned, encrypted and extracted without
   plaintext persistence.
2. Unsupported, unsafe, infected, password-protected, corrupted and textless
   documents have correct safe outcomes.
3. Identical bytes from separate messages reuse one document/extraction while
   retaining both delivery references.
4. Tests use fake ClamAV/storage and cover AES-GCM round-trip, deduplication,
   retry bounds and no sensitive log output.

## Required validation

```powershell
cd cv-matcher-backend
.\gradlew.bat test --tests "*Document*" --tests "*Clam*" --tests "*Encryption*"
.\gradlew.bat test
docker compose --env-file .env.example -f ..\compose.yaml config --quiet
git diff --check
```

## Review gate

Technical review is limited to validation, scan, encryption, deduplication,
extraction and their durable integration with 002B-2.
