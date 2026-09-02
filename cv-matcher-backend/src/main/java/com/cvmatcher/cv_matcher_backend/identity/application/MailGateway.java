package com.cvmatcher.cv_matcher_backend.identity;

public interface MailGateway {
    void send(String purpose, String email, String opaqueToken);
}
