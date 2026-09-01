package com.cvmatcher.cv_matcher_backend.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphClient;
import com.cvmatcher.cv_matcher_backend.ingestion.repository.MatchingJobMessageRepository;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphTransientException;
import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftReauthorizationRequiredException;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJob;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DurableMatchingJobIngestionWorkerTest {
    @Test
    void persistsUniqueMessagesAndMovesToScanningWithTruncationWarning() {
        MatchingJobRepository jobs = mock(MatchingJobRepository.class);
        MatchingJobMessageRepository messages = mock(MatchingJobMessageRepository.class);
        MicrosoftGraphClient graph = mock(MicrosoftGraphClient.class);
        MatchingJob job = new MatchingJob("Role", "Description", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"), "correlation");
        when(jobs.claimQueuedJob(job.getId())).thenReturn(1);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        var message = new MicrosoftGraphClient.GraphMessage("immutable-id", Instant.parse("2026-08-01T01:00:00Z"), List.of());
        when(graph.discoverInboxMessages(job.getFrom(), job.getTo()))
                .thenReturn(new MicrosoftGraphClient.DiscoveryResult(List.of(message), 1, 0, 0, true));
        when(messages.findByMatchingJobIdAndGraphMessageId(job.getId(), "immutable-id")).thenReturn(Optional.empty());

        new DurableMatchingJobIngestionWorker(jobs, messages, graph, ignored -> { }).process(job.getId());

        verify(messages).save(org.mockito.ArgumentMatchers.any());
        assertThat(job.getStatus()).isEqualTo(MatchingJobStatus.SCANNING_DOCUMENTS);
        assertThat(job.getProcessedMessages()).isEqualTo(1);
        assertThat(job.getSafeWarning()).isEqualTo("RANGE_TRUNCATED");
    }

    @Test
    void marksJobFailedWhenGraphRetriesAreExhausted() {
        MatchingJobRepository jobs = mock(MatchingJobRepository.class);
        MatchingJobMessageRepository messages = mock(MatchingJobMessageRepository.class);
        MicrosoftGraphClient graph = mock(MicrosoftGraphClient.class);
        MatchingJob job = new MatchingJob("Role", "Description", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"), "correlation");
        when(jobs.claimQueuedJob(job.getId())).thenReturn(1);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(graph.discoverInboxMessages(job.getFrom(), job.getTo())).thenThrow(new MicrosoftGraphTransientException("temporary"));

        new DurableMatchingJobIngestionWorker(jobs, messages, graph, ignored -> { }).process(job.getId());

        assertThat(job.getStatus()).isEqualTo(MatchingJobStatus.INGESTION_FAILED);
    }

    @Test
    void marksJobForReauthorizationWhenMicrosoftConnectionIsInvalid() {
        MatchingJobRepository jobs = mock(MatchingJobRepository.class);
        MatchingJobMessageRepository messages = mock(MatchingJobMessageRepository.class);
        MicrosoftGraphClient graph = mock(MicrosoftGraphClient.class);
        MatchingJob job = new MatchingJob("Role", "Description", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"), "correlation");
        when(jobs.claimQueuedJob(job.getId())).thenReturn(1);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(graph.discoverInboxMessages(job.getFrom(), job.getTo())).thenThrow(new MicrosoftReauthorizationRequiredException("reauthorize"));

        new DurableMatchingJobIngestionWorker(jobs, messages, graph, ignored -> { }).process(job.getId());

        assertThat(job.getStatus()).isEqualTo(MatchingJobStatus.REAUTHORIZATION_REQUIRED);
    }
}
