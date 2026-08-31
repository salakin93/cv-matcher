package com.cvmatcher.cv_matcher_backend.matchingjob.domain;

public enum MatchingJobStatus {
    QUEUED,
    INGESTING_EMAILS,
    SCANNING_DOCUMENTS,
    EXTRACTING_TEXT,
    TEXT_EXTRACTION_COMPLETE,
    ANALYZING_CANDIDATES,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    INGESTION_FAILED,
    REAUTHORIZATION_REQUIRED;

    public boolean isTerminal() {
        return this == COMPLETED
                || this == COMPLETED_WITH_WARNINGS
                || this == INGESTION_FAILED
                || this == REAUTHORIZATION_REQUIRED;
    }
}
