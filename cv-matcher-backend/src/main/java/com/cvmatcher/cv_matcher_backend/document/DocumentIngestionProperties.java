package com.cvmatcher.cv_matcher_backend.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.job.documents")
public record DocumentIngestionProperties(boolean enabled, Duration pollDelay, Duration leaseDuration,
                                           long maxDocumentBytes, int maxDocumentsPerJob, long maxBytesPerJob,
                                           int maxAttachmentsPerMessage, String storageRoot, String encryptionKey,
                                           int encryptionKeyVersion, String antivirusMode, String antivirusHost,
                                           int antivirusPort, Duration antivirusTimeout) {
    public DocumentIngestionProperties {
        if (pollDelay == null || pollDelay.isNegative() || pollDelay.isZero() || leaseDuration == null
                || leaseDuration.isNegative() || leaseDuration.isZero() || maxDocumentBytes < 1
                || maxDocumentsPerJob < 1 || maxBytesPerJob < maxDocumentBytes || maxAttachmentsPerMessage < 1
                || encryptionKeyVersion < 1 || antivirusMode == null || antivirusMode.isBlank()
                || antivirusHost == null || antivirusHost.isBlank() || antivirusPort < 1 || antivirusPort > 65535
                || antivirusTimeout == null || antivirusTimeout.isNegative() || antivirusTimeout.isZero()) {
            throw new IllegalArgumentException("invalid document ingestion configuration");
        }
    }
}
