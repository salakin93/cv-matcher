package com.cvmatcher.cv_matcher_backend.matchingjob.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "matching_job_requirement")
public class MatchingJobRequirement {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matching_job_id", nullable = false)
    private MatchingJob job;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private int weight;

    @Column(nullable = false)
    private boolean mandatory;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected MatchingJobRequirement() {
    }

    public String getDescription() { return description; }
    public int getWeight() { return weight; }
    public boolean isMandatory() { return mandatory; }
    public int getDisplayOrder() { return displayOrder; }

    MatchingJobRequirement(MatchingJob job, String description, int weight, boolean mandatory, int displayOrder) {
        this.id = UUID.randomUUID();
        this.job = job;
        this.description = description;
        this.weight = weight;
        this.mandatory = mandatory;
        this.displayOrder = displayOrder;
    }
}
