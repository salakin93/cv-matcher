package com.cvmatcher.cv_matcher_backend.matchingjob.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "matching_job_event")
public class MatchingJobEvent {

    @Id
    private UUID id;

    @Column(name = "matching_job_id", nullable = false)
    private UUID matchingJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 64)
    private MatchingJobStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 64)
    private MatchingJobStatus newStatus;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "safe_details", length = 500)
    private String safeDetails;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MatchingJobEvent() {
    }

    private MatchingJobEvent(
            UUID matchingJobId,
            MatchingJobStatus previousStatus,
            MatchingJobStatus newStatus,
            String eventType,
            String safeDetails) {
        this.id = UUID.randomUUID();
        this.matchingJobId = matchingJobId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.eventType = eventType;
        this.safeDetails = safeDetails;
        this.createdAt = Instant.now();
    }

    public static MatchingJobEvent created(UUID matchingJobId) {
        return new MatchingJobEvent(matchingJobId, null, MatchingJobStatus.QUEUED, "JOB_CREATED", "Job queued");
    }

    public UUID getMatchingJobId() {
        return matchingJobId;
    }

    public MatchingJobStatus getNewStatus() {
        return newStatus;
    }

    public String getEventType() {
        return eventType;
    }
}
