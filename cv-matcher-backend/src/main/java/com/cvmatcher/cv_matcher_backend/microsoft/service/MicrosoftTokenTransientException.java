package com.cvmatcher.cv_matcher_backend.microsoft.service;

public class MicrosoftTokenTransientException extends RuntimeException {
    public MicrosoftTokenTransientException(String message, Throwable cause) { super(message, cause); }
    public MicrosoftTokenTransientException(String message) { super(message); }
}
