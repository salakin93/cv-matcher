package com.cvmatcher.cv_matcher_backend.identity.insfrastructure.mail;

import com.cvmatcher.cv_matcher_backend.identity.application.MailGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class UnavailableProductionMailGateway implements MailGateway {
    @Override
    public void send(MailCommand command) {
        throw new IllegalStateException("Production mail delivery is not configured");
    }
}
