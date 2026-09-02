package com.cvmatcher.cv_matcher_backend.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test", "default"})
public class NoopMailGateway implements MailGateway {
    public void send(String purpose, String email, String opaqueToken) {
    }
}
