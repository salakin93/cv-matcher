package com.cvmatcher.cv_matcher_backend.identity.insfrastructure.mail;

import com.cvmatcher.cv_matcher_backend.identity.application.MailGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@Profile({"local", "test"})
public class NoopMailGateway implements MailGateway {
    private final ConcurrentLinkedQueue<MailCommand> commands = new ConcurrentLinkedQueue<>();

    @Override
    public void send(MailCommand command) {
        commands.add(command);
    }

    public List<MailCommand> sentCommands() {
        return List.copyOf(commands);
    }

    public void clear() {
        commands.clear();
    }
}
