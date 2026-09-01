package com.cvmatcher.cv_matcher_backend.ingestion.service;

import com.cvmatcher.cv_matcher_backend.ingestion.domain.MatchingJobMessage;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphClient;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphRequestException;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphTransientException;
import com.cvmatcher.cv_matcher_backend.ingestion.repository.MatchingJobMessageRepository;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftReauthorizationRequiredException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DurableMatchingJobIngestionWorker implements MatchingJobIngestionDispatcher {
    private final MatchingJobRepository jobs;
    private final MatchingJobMessageRepository messages;
    private final MicrosoftGraphClient graph;

    public DurableMatchingJobIngestionWorker(MatchingJobRepository jobs, MatchingJobMessageRepository messages, MicrosoftGraphClient graph) {
        this.jobs = jobs; this.messages = messages; this.graph = graph;
    }

    @Override public void dispatchAfterCommit(UUID jobId) { processAsync(jobId); }
    @Async public void processAsync(UUID jobId) { process(jobId); }

    @Transactional
    public void process(UUID jobId) {
        if (jobs.claimQueuedJob(jobId) != 1) return;
        var job = jobs.findById(jobId).orElseThrow();
        try {
            var discovered = graph.discoverInboxMessages(job.getFrom(), job.getTo());
            int newlyPersisted = 0;
            for (var message : discovered.messages()) {
                if (messages.findByMatchingJobIdAndGraphMessageId(jobId, message.immutableId()).isEmpty()) {
                    messages.save(new MatchingJobMessage(jobId, message.immutableId(), message.receivedAt()));
                    newlyPersisted++;
                }
            }
            job.markMessagesProcessed(newlyPersisted, discovered.truncated());
            job.transitionTo(MatchingJobStatus.SCANNING_DOCUMENTS);
        } catch (MicrosoftReauthorizationRequiredException exception) {
            job.transitionTo(MatchingJobStatus.REAUTHORIZATION_REQUIRED);
        } catch (MicrosoftGraphTransientException | MicrosoftGraphRequestException exception) {
            job.transitionTo(MatchingJobStatus.INGESTION_FAILED);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverAfterStartup() {
        jobs.releaseInterruptedClaims();
        List<UUID> recoverable = jobs.findByStatusInOrderByCreatedAtAsc(List.of(MatchingJobStatus.QUEUED, MatchingJobStatus.INGESTING_EMAILS))
                .stream().map(job -> job.getId()).toList();
        recoverable.forEach(this::dispatchAfterCommit);
    }
}
