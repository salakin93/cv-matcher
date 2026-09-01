package com.cvmatcher.cv_matcher_backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.cvmatcher.cv_matcher_backend.ingestion.service.AsyncMatchingJobIngestionDispatcher;
import com.cvmatcher.cv_matcher_backend.ingestion.service.DurableMatchingJobIngestionWorker;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AsyncMatchingJobIngestionDispatcherIntegrationTest {
    @Autowired
    private AsyncMatchingJobIngestionDispatcher dispatcher;

    @MockitoBean
    private DurableMatchingJobIngestionWorker worker;

    @Test
    void delegatesAfterCallerReturnsWithoutBlockingForWorkerCompletion() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch allowWorkerFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            workerStarted.countDown();
            assertThat(allowWorkerFinish.await(2, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(worker).process(any(UUID.class));

        UUID jobId = UUID.randomUUID();
        dispatcher.dispatchAfterCommit(jobId);

        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(allowWorkerFinish.getCount()).isOne();
        allowWorkerFinish.countDown();
    }
}
