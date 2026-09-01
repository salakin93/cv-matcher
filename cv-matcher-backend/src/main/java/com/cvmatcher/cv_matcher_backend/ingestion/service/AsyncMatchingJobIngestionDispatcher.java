package com.cvmatcher.cv_matcher_backend.ingestion.service;

import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AsyncMatchingJobIngestionDispatcher implements MatchingJobIngestionDispatcher {
    private final DurableMatchingJobIngestionWorker worker;

    public AsyncMatchingJobIngestionDispatcher(DurableMatchingJobIngestionWorker worker) {
        this.worker = worker;
    }

    @Override
    @Async
    public void dispatchAfterCommit(UUID jobId) {
        worker.process(jobId);
    }
}
