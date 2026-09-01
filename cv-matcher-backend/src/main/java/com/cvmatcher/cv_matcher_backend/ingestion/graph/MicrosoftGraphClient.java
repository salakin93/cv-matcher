package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import java.time.Instant;
import java.util.List;

/** Boundary for the minimal Graph data required by document ingestion. */
public interface MicrosoftGraphClient {
    DiscoveryResult discoverInboxMessages(Instant fromInclusive, Instant toInclusive);

    record DiscoveryResult(
            List<GraphMessage> messages,
            int acceptedMessageCount,
            int acceptedAttachmentCount,
            long acceptedAttachmentBytes,
            boolean truncated) {}

    record GraphMessage(String immutableId, Instant receivedAt, List<GraphAttachment> attachments) {}
    record GraphAttachment(String id, String name, String contentType, boolean inline, byte[] content) {}
}
