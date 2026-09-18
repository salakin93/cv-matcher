package com.cvmatcher.cv_matcher_backend.outlook.application;

public final class AttachmentException extends RuntimeException {
    public enum Kind { REAUTHORIZATION_REQUIRED, TEMPORARY_FAILURE, PROTOCOL_ERROR }
    private final Kind kind;
    public AttachmentException(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
