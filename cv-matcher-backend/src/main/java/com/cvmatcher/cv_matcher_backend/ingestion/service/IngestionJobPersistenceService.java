package com.cvmatcher.cv_matcher_backend.ingestion.service;

import com.cvmatcher.cv_matcher_backend.ingestion.domain.MatchingJobMessage;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphClient;
import com.cvmatcher.cv_matcher_backend.ingestion.repository.MatchingJobMessageRepository;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionJobPersistenceService {
    private final MatchingJobRepository jobs;
    private final MatchingJobMessageRepository messages;

    public IngestionJobPersistenceService(MatchingJobRepository jobs, MatchingJobMessageRepository messages) {
        this.jobs = jobs; this.messages = messages;
    }

    @Transactional
    public JobSnapshot claimAndLoadSnapshot(UUID jobId) {
        if (jobs.claimQueuedJob(jobId) != 1) return null;
        var job = jobs.findById(jobId).orElseThrow();
        return new JobSnapshot(job.getId(), job.getFrom(), job.getTo());
    }

    @Transactional
    public void persistDiscovery(JobSnapshot snapshot, MicrosoftGraphClient.DiscoveryResult discovered) {
        var job = jobs.findById(snapshot.jobId()).orElseThrow();
        int added = 0;
        for (var message : discovered.messages()) {
            if (messages.findByMatchingJobIdAndGraphMessageId(snapshot.jobId(), message.immutableId()).isEmpty()) {
                messages.save(new MatchingJobMessage(snapshot.jobId(), message.immutableId(), message.receivedAt()));
                added++;
            }
        }
        job.markMessagesProcessed(added, discovered.truncated());
        job.transitionTo(MatchingJobStatus.SCANNING_DOCUMENTS);
    }

    @Transactional
    public void markFailed(UUID jobId) { jobs.findById(jobId).ifPresent(job -> job.transitionTo(MatchingJobStatus.INGESTION_FAILED)); }

    @Transactional
    public void markReauthorizationRequired(UUID jobId) { jobs.findById(jobId).ifPresent(job -> job.transitionTo(MatchingJobStatus.REAUTHORIZATION_REQUIRED)); }

    public record JobSnapshot(UUID jobId, Instant from, Instant to) {}
}
