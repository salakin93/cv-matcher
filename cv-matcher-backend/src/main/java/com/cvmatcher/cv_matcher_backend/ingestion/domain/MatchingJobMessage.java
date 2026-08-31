package com.cvmatcher.cv_matcher_backend.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "matching_job_message")
public class MatchingJobMessage {
    @Id private UUID id;
    @Column(name = "matching_job_id", nullable = false) private UUID matchingJobId;
    @Column(name = "graph_message_id", nullable = false) private String graphMessageId;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected MatchingJobMessage() {}
    public MatchingJobMessage(UUID jobId, String graphMessageId, Instant receivedAt) {
        this.id = UUID.randomUUID(); this.matchingJobId = jobId; this.graphMessageId = graphMessageId;
        this.receivedAt = receivedAt; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
}
