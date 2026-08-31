package com.cvmatcher.cv_matcher_backend.matchingjob.service;

import java.util.UUID;

public class MatchingJobConflictException extends RuntimeException {

    private final UUID activeJobId;

    public MatchingJobConflictException(UUID activeJobId) {
        super("A matching job is already running");
        this.activeJobId = activeJobId;
    }

    public UUID getActiveJobId() {
        return activeJobId;
    }
}
