package com.cvmatcher.cv_matcher_backend.job.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Internal durable coordination boundary for future workers; it does not execute work. */
public interface MatchingJobWorkerPort {
    Optional<JobService.ClaimedJob> claimNext(String workerId, Duration leaseDuration);
    boolean renewLease(UUID jobId, String workerId, Duration leaseDuration);
    boolean transitionClaimed(UUID jobId, String workerId, JobService.Status nextStatus);
    boolean finishClaimed(UUID jobId, String workerId, JobService.Status terminalStatus, String failureCode);
}
