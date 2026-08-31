package com.cvmatcher.cv_matcher_backend.matchingjob.api;

import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import java.time.Instant;
import java.util.UUID;

public record MatchingJobCreatedResponse(
        UUID jobId,
        MatchingJobStatus status,
        String statusUrl,
        Instant createdAt) {
}
