package com.cvmatcher.cv_matcher_backend.matchingjob.api;

import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobMode;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import java.time.Instant;
import java.util.UUID;

public record MatchingJobStatusResponse(
        UUID jobId,
        MatchingJobStatus status,
        MatchingJobMode jobMode,
        Instant from,
        Instant to,
        Instant createdAt,
        Instant updatedAt,
        MatchingJobCounters counters) {

    public record MatchingJobCounters(
            int processedMessages,
            int acceptedDocuments,
            int ignoredDocuments,
            int duplicateDocuments,
            int analyzedCandidates,
            int analysisFailures) {
        public static MatchingJobCounters empty() {
            return new MatchingJobCounters(0, 0, 0, 0, 0, 0);
        }
    }
}
