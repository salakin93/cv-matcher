package com.cvmatcher.cv_matcher_backend.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingested_document")
public class IngestedDocument {
    @Id private UUID id;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(name = "original_artifact_path", nullable = false) private String originalArtifactPath;
    @Column(name = "original_nonce", nullable = false) private byte[] originalNonce;
    @Column(name = "text_artifact_path") private String textArtifactPath;
    @Column(name = "text_nonce") private byte[] textNonce;
    @Column(name = "encryption_key_version", nullable = false) private String encryptionKeyVersion;
    @Column(name = "content_type", nullable = false) private String contentType;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Enumerated(EnumType.STRING) @Column(name = "extraction_status", nullable = false) private DocumentStatus extractionStatus;
    @Enumerated(EnumType.STRING) @Column(name = "ignored_reason") private IgnoredDocumentReason ignoredReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "retained_until", nullable = false) private Instant retainedUntil;
    protected IngestedDocument() {}
    public IngestedDocument(String sha256, String originalPath, byte[] originalNonce, String textPath, byte[] textNonce,
            String contentType, long sizeBytes, DocumentStatus status, IgnoredDocumentReason reason) {
        this.id = UUID.randomUUID(); this.sha256 = sha256; this.originalArtifactPath = originalPath;
        this.originalNonce = originalNonce; this.textArtifactPath = textPath; this.textNonce = textNonce;
        this.encryptionKeyVersion = "v1"; this.contentType = contentType; this.sizeBytes = sizeBytes;
        this.extractionStatus = status; this.ignoredReason = reason; this.createdAt = Instant.now();
        this.retainedUntil = createdAt.plus(java.time.Duration.ofDays(90));
    }
    public UUID getId() { return id; }
}
