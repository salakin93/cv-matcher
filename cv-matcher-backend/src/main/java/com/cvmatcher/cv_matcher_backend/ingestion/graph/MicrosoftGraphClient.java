package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import java.time.Instant;
import java.util.List;

/** Boundary for the minimal Graph data required by document ingestion. */
public interface MicrosoftGraphClient {
    List<GraphMessage> inboxMessages(Instant fromInclusive, Instant toInclusive);

    record GraphMessage(String immutableId, Instant receivedAt, List<GraphAttachment> attachments) {}
    record GraphAttachment(String id, String name, String contentType, boolean inline, byte[] content) {}
}
