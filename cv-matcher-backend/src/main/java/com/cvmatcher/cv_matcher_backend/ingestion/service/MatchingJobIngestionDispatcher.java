package com.cvmatcher.cv_matcher_backend.ingestion.service;

import java.util.UUID;

public interface MatchingJobIngestionDispatcher {
    void dispatchAfterCommit(UUID jobId);
}
