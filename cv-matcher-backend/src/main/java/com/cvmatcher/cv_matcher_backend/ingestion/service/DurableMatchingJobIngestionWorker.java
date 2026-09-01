package com.cvmatcher.cv_matcher_backend.ingestion.service;

import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphClient;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphRequestException;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphTransientException;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftReauthorizationRequiredException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DurableMatchingJobIngestionWorker {
    private final MatchingJobRepository jobs;
    private final IngestionJobPersistenceService persistence;
    private final MicrosoftGraphClient graph;
    private final MatchingJobIngestionDispatcher dispatcher;

    public DurableMatchingJobIngestionWorker(MatchingJobRepository jobs, IngestionJobPersistenceService persistence, MicrosoftGraphClient graph,
            @Lazy MatchingJobIngestionDispatcher dispatcher) {
        this.jobs = jobs; this.persistence = persistence; this.graph = graph; this.dispatcher = dispatcher;
    }

    public void process(UUID jobId) {
        var snapshot = persistence.claimAndLoadSnapshot(jobId);
        if (snapshot == null) return;
        try {
            persistence.persistDiscovery(snapshot, graph.discoverInboxMessages(snapshot.from(), snapshot.to()));
        } catch (MicrosoftReauthorizationRequiredException exception) {
            persistence.markReauthorizationRequired(jobId);
        } catch (MicrosoftGraphTransientException | MicrosoftGraphRequestException exception) {
            persistence.markFailed(jobId);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverAfterStartup() {
        jobs.releaseInterruptedClaims();
        List<UUID> recoverable = jobs.findByStatusInOrderByCreatedAtAsc(List.of(MatchingJobStatus.QUEUED, MatchingJobStatus.INGESTING_EMAILS))
                .stream().map(job -> job.getId()).toList();
        recoverable.forEach(dispatcher::dispatchAfterCommit);
    }
}
