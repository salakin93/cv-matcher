package com.cvmatcher.cv_matcher_backend.matchingjob.service;

import java.util.UUID;

public class MatchingJobNotFoundException extends RuntimeException {

    public MatchingJobNotFoundException(UUID jobId) {
        super("Matching job %s was not found".formatted(jobId));
    }
}
