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
@Table(name = "matching_job_document_reference")
public class MatchingJobDocumentReference {
    @Id private UUID id;
    @Column(name = "matching_job_id", nullable = false) private UUID matchingJobId;
    @Column(name = "matching_job_message_id", nullable = false) private UUID matchingJobMessageId;
    @Column(name = "graph_attachment_id", nullable = false) private String graphAttachmentId;
    @Column(name = "ingested_document_id") private UUID ingestedDocumentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DocumentStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "ignored_reason") private IgnoredDocumentReason ignoredReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected MatchingJobDocumentReference() {}
    public MatchingJobDocumentReference(UUID jobId, UUID messageId, String attachmentId, UUID documentId,
            DocumentStatus status, IgnoredDocumentReason reason) {
        this.id = UUID.randomUUID(); this.matchingJobId = jobId; this.matchingJobMessageId = messageId;
        this.graphAttachmentId = attachmentId; this.ingestedDocumentId = documentId; this.status = status;
        this.ignoredReason = reason; this.createdAt = Instant.now();
    }
}
