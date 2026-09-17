package com.cvmatcher.cv_matcher_backend.outlook.application;

import java.time.Instant;
import java.util.List;

/** Reads only the minimal immutable message metadata needed by document ingestion. */
public interface InboxDiscoveryPort {
    Page listInboxMessages(Instant fromUtc, Instant toUtcExclusive, String nextLink, int pageSize);

    default Page listInboxMessages(Instant fromUtc, Instant toUtcExclusive, String nextLink, int pageSize, Runnable beforeRequest) {
        beforeRequest.run();
        return listInboxMessages(fromUtc, toUtcExclusive, nextLink, pageSize);
    }

    record Page(List<Message> messages, String nextLink) {}
    record Message(String immutableId, Instant receivedAt, boolean hasAttachments) {}
}
