package com.cvmatcher.cv_matcher_backend.matchingjob.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "matching_job")
public class MatchingJob {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 10000)
    private String description;

    @Column(name = "from_timestamp", nullable = false)
    private Instant from;

    @Column(name = "to_timestamp", nullable = false)
    private Instant to;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private MatchingJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_mode", nullable = false, length = 32)
    private MatchingJobMode jobMode;

    @Column(name = "retry_of_job_id")
    private UUID retryOfJobId;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MatchingJobRequirement> requirements = new ArrayList<>();

    protected MatchingJob() {
    }

    public MatchingJob(String title, String description, Instant from, Instant to, String correlationId) {
        this(title, description, from, to, correlationId, null);
    }

    private MatchingJob(String title, String description, Instant from, Instant to, String correlationId, UUID retryOfJobId) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.description = description;
        this.from = from;
        this.to = to;
        this.status = MatchingJobStatus.QUEUED;
        this.jobMode = MatchingJobMode.FULL;
        this.retryOfJobId = retryOfJobId;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static MatchingJob retryOf(MatchingJob previousJob, String correlationId) {
        MatchingJob retry = new MatchingJob(previousJob.title, previousJob.description, previousJob.from,
                previousJob.to, correlationId, previousJob.id);
        previousJob.requirements.forEach(requirement -> retry.addRequirement(
                requirement.getDescription(), requirement.getWeight(), requirement.isMandatory(), requirement.getDisplayOrder()));
        return retry;
    }

    public void addRequirement(String description, int weight, boolean mandatory, int displayOrder) {
        requirements.add(new MatchingJobRequirement(this, description, weight, mandatory, displayOrder));
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Instant getFrom() {
        return from;
    }

    public Instant getTo() {
        return to;
    }

    public MatchingJobStatus getStatus() {
        return status;
    }

    public MatchingJobMode getJobMode() {
        return jobMode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getRetryOfJobId() { return retryOfJobId; }
}
