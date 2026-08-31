package com.cvmatcher.cv_matcher_backend.ingestion.domain;

public enum IgnoredDocumentReason {
    UNSUPPORTED_FORMAT,
    EMPTY_FILE,
    OVERSIZED,
    MALWARE_DETECTED,
    MALWARE_SCAN_FAILED,
    PASSWORD_PROTECTED,
    TEXT_EXTRACTION_FAILED
}
