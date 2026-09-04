package com.cvmatcher.cv_matcher_backend.identity.application;

public interface MailGateway {
    void send(MailCommand command);

    record MailCommand(Purpose purpose, String recipient, String opaqueToken) {
    }

    enum Purpose {
        EMAIL_VERIFICATION,
        PASSWORD_RESET,
        EMAIL_CHANGE
    }
}
