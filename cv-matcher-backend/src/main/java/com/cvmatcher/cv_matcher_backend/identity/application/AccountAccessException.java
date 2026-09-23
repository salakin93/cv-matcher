package com.cvmatcher.cv_matcher_backend.identity.application;

public class AccountAccessException extends RuntimeException {
    public enum Reason {
        EMAIL_VERIFICATION_REQUIRED,
        ACCOUNT_TEMPORARILY_LOCKED
    }

    private final Reason reason;

    public AccountAccessException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
