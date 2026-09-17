package com.cvmatcher.cv_matcher_backend.outlook.application;

import org.springframework.stereotype.Component;

@Component
final class OutlookInboxAuthorizationService implements OutlookInboxAuthorizationPort {
    private static final String INBOX_SCOPE = "Mail.ReadBasic";
    private final OutlookConnectionStore connections;

    OutlookInboxAuthorizationService(OutlookConnectionStore connections) {
        this.connections = connections;
    }

    @Override
    public void requireInboxReadBasic() {
        var status = connections.status();
        if (!"CONNECTED".equals(status.status()) || !status.grantedScopes().contains(INBOX_SCOPE)) {
            connections.requireReauthorization();
            throw new InboxDiscoveryException(InboxDiscoveryException.Kind.REAUTHORIZATION_REQUIRED);
        }
    }
}
