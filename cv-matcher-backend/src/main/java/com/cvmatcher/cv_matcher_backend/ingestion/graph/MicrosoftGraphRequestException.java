package com.cvmatcher.cv_matcher_backend.ingestion.graph;

/** Non-transient Graph failure. Its provider response is intentionally not retained. */
public class MicrosoftGraphRequestException extends RuntimeException {
    public MicrosoftGraphRequestException(String message) { super(message); }
}
