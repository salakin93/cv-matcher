# Spec 002C - Claude analysis and final report

## Status

Ready after 002A and 002B are complete.

## Objective

Analyze the clean extracted documents of a `TEXT_EXTRACTION_COMPLETE` job with Anthropic Claude Sonnet 5, calculate deterministic requirement-weighted scores server-side, and expose the final ranked report.

## Scope and privacy

- Requires 002A job foundation and 002B scanned, encrypted extracted text.
- Send one CV at a time to Claude, never a batch of CVs.
- Send only extracted text plus vacancy title, description, and requirements. Never send email metadata, original document bytes, tokens, or credentials.
- Claude output is Spanish. `summary`, `explanation`, and `evidence` are at most 300 characters and must rely only on text evidence, never inference.

## Claude response contract

For one document, require strict JSON:

```json
{
  "requirements": [
    {
      "requirementIndex": 0,
      "compatibilityScore": 85,
      "explanation": "Short Spanish explanation.",
      "evidence": "Short Spanish evidence."
    }
  ],
  "summary": "Short Spanish candidate compatibility summary."
}
```

- One result is required for every input requirement.
- Indexes are zero-based input-requirement positions.
- Score is an integer from 0 through 100. Missing or insufficient evidence must score 0 with empty evidence.
- Validate response against a strict JSON schema. An invalid response gets one corrective retry; still-invalid output becomes `ANALYSIS_FAILED` with no invented score.

## Scoring

Backend, not Claude, calculates:

```text
mandatoryScore = sum(weight * compatibilityScore for mandatory requirements)
                 / sum(weight for mandatory requirements)

optionalBonus = 20 * (sum(weight * compatibilityScore for optional requirements)
                 / sum(weight for optional requirements)) / 100

totalScore = min(100, mandatoryScore + optionalBonus)
```

- A vacancy has at least one mandatory requirement.
- When there are no optional requirements, optional bonus is 0.
- Optional requirements can add at most 20 points and never reduce the score.

## Job lifecycle and failures

1. Job transitions from `TEXT_EXTRACTION_COMPLETE` to `ANALYZING_CANDIDATES`.
2. Anthropic transient/transport failures retry at most two times.
3. A document with unavailable/invalid analysis is `ANALYSIS_FAILED`.
4. If at least one document is analyzed, job is `COMPLETED` or `COMPLETED_WITH_WARNINGS`.
5. If no document can be analyzed, job is `INGESTION_FAILED`.

## Final report

`GET /api/matching-jobs/{jobId}` returns a completed report with safe aggregate counters and candidates sorted by `totalScore` descending, then `mandatoryScore` descending.

Each candidate exposes `rank`, opaque `documentId`, `mandatoryScore`, `optionalBonus`, `totalScore`, validated per-requirement result, and summary. Candidate name and structured profile extraction are deferred.

Analysis and report data use the same protected 90-day retention as the source document and are never written to logs.

## Acceptance criteria

1. Only scanned, extracted documents reach Claude.
2. Claude receives one document at a time and returns validated Spanish JSON.
3. Missing evidence scores 0; backend scoring follows the approved formula exactly.
4. Invalid or failed document analysis does not invent a score and yields warnings unless all analyses fail.
5. Completed reports are ranked deterministically and visible only through protected job status.
6. Tests use Anthropic stubs, never real API keys or CV content.
# Approved AI safety, report, budget, and retry details

This section supersedes any conflicting prompt, report, retry, budget, audit, or ranking-use instruction in this specification.

## Prompt safety and audit

- Use the Claude Sonnet 5 model configured in `docs/anthopic-setup.md` with a versioned system prompt.
- The system prompt treats CV text as untrusted data. It instructs Claude to ignore commands, role changes, or instructions found in CV text; use only CV evidence; never infer facts; and return only the approved JSON.
- Store model identifier, prompt version, timestamp, safe processing status, and backend-calculated scores for audit.
- Never store or log the full prompt, CV text, raw provider response, token usage content, or evidence in logs.

## Input budget and provider execution

- Send at most 50,000 normalized extracted-text characters per document to Claude.
- A document exceeding that limit is `IGNORED` with safe reason `TEXT_TOO_LARGE` and is never sent to Claude.
- Process at most three Claude calls concurrently within the globally active job.
- Respect provider `Retry-After` and rate limits. Anthropic transient failures retain the existing limit of two retries.

## Completed-report contract

`GET /api/matching-jobs/{jobId}` returns the following structure when the job is terminal:

```json
{
  "jobId": "uuid",
  "status": "COMPLETED_WITH_WARNINGS",
  "createdAt": "2026-08-31T15:00:00Z",
  "completedAt": "2026-08-31T15:08:12Z",
  "summary": {
    "processedMessages": 15,
    "acceptedDocuments": 10,
    "ignoredDocuments": 2,
    "duplicateDocuments": 3,
    "analyzedCandidates": 9,
    "analysisFailures": 1,
    "durationMilliseconds": 492000
  },
  "candidates": [
    {
      "rank": 1,
      "documentId": "uuid",
      "mandatoryScore": 82.5,
      "optionalBonus": 11.4,
      "totalScore": 93.9,
      "requirements": [
        {
          "requirementIndex": 0,
          "compatibilityScore": 85,
          "explanation": "Short Spanish explanation.",
          "evidence": "Short Spanish evidence."
        }
      ],
      "summary": "Short Spanish candidate compatibility summary."
    }
  ],
  "disclaimer": "AI compatibility scores are decision-support only and do not make hiring or rejection decisions."
}
```

- Candidates remain sorted by `totalScore` descending and then `mandatoryScore` descending.
- The frontend displays the disclaimer whenever analysis results are shown.

## Analysis-only retry

- If a terminal `INGESTION_FAILED` job has one or more protected `TEXT_EXTRACTED` documents and all Claude analyses failed, retry creates a new auditable job with `retryOfJobId` and mode `ANALYSIS_ONLY`.
- `ANALYSIS_ONLY` reuses protected extracted text and does not call Microsoft Graph, ClamAV, document storage, or text extraction again.
# Cross-spec scoring and retry decisions

This section supersedes any conflicting scoring precision, ordering, or retry instruction in this specification.

- `ANALYSIS_ONLY` is allowed only when protected extracted text exists and all prior Claude analyses failed. It does not call Microsoft Graph, ClamAV, storage, or extractors.
- Backend calculates scores using decimal arithmetic and rounds `mandatoryScore`, `optionalBonus`, and `totalScore` to two decimal places using `HALF_UP` for API output.
- Candidates are ordered by `totalScore` descending, then `mandatoryScore` descending, then `documentId` ascending for a stable final tie-breaker.
# Analysis entry states

This section supersedes any conflicting analysis-start instruction in this specification.

- A `FULL` job enters `ANALYZING_CANDIDATES` automatically only after 002B persists `TEXT_EXTRACTION_COMPLETE` with at least one extracted document.
- An `ANALYSIS_ONLY` job enters `ANALYZING_CANDIDATES` directly from `QUEUED` and skips Microsoft Graph, ClamAV, document storage, and extraction.
- The worker persists `ANALYZING_CANDIDATES` before any Anthropic request.

