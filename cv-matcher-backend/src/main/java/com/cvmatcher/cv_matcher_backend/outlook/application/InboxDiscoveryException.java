package com.cvmatcher.cv_matcher_backend.outlook.application;

public final class InboxDiscoveryException extends RuntimeException {
    public enum Kind { REAUTHORIZATION_REQUIRED, TEMPORARY_FAILURE, PROTOCOL_ERROR, CANCELLED }
    private final Kind kind;
    public InboxDiscoveryException(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
