package com.cvmatcher.cv_matcher_backend.ingestion.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AsyncMatchingJobIngestionDispatcherTest {
    @Test
    void delegatesProcessingToDurableWorker() {
        DurableMatchingJobIngestionWorker worker = mock(DurableMatchingJobIngestionWorker.class);
        UUID jobId = UUID.randomUUID();

        new AsyncMatchingJobIngestionDispatcher(worker).dispatchAfterCommit(jobId);

        verify(worker).process(jobId);
    }
}
