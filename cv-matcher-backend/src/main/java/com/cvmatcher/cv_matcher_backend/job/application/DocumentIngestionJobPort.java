package com.cvmatcher.cv_matcher_backend.job.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Internal document-ingestion coordination boundary; it exposes no job tables. */
public interface DocumentIngestionJobPort {
    Optional<IngestionClaim> claimNextIngestion(String workerId, Duration leaseDuration);
    boolean renewLease(UUID jobId, String workerId, Duration leaseDuration);
    boolean isCancelled(UUID jobId);
    List<DiscoveredMessageReference> discoveredMessages(UUID jobId, String workerId);
    boolean persistDocument(UUID jobId, String workerId, DocumentWrite document);
    boolean completeIngestion(UUID jobId, String workerId, boolean hasAvailableDocuments);
    boolean finishIngestion(UUID jobId, String workerId, IngestionTerminalStatus terminalStatus, String failureCode);

    enum IngestionTerminalStatus { FAILED, REAUTHORIZATION_REQUIRED }
    record IngestionClaim(UUID jobId, java.time.Instant receivedFromUtc, java.time.Instant receivedToUtcExclusive, java.time.Instant leaseUntil) {}
    record DiscoveredMessageReference(String graphMessageId, java.time.Instant receivedAt, boolean hasAttachments) {}
    record DocumentWrite(String graphMessageId, String graphAttachmentId, java.time.Instant receivedAt, String format,
                         long sizeBytes, String contentHash, String storageKey, int encryptionKeyVersion,
                         String disposition, String reasonCode) {}
}
