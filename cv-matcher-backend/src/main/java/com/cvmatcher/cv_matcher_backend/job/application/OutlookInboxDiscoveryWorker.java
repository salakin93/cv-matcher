package com.cvmatcher.cv_matcher_backend.job.application;

import com.cvmatcher.cv_matcher_backend.job.JobDiscoveryProperties;
import com.cvmatcher.cv_matcher_backend.outlook.application.InboxDiscoveryException;
import com.cvmatcher.cv_matcher_backend.outlook.application.InboxDiscoveryPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.job.discovery", name = "enabled", havingValue = "true")
public class OutlookInboxDiscoveryWorker {
    private final JobService jobs;
    private final InboxDiscoveryPort inbox;
    private final JobDiscoveryProperties properties;
    private final MeterRegistry metrics;
    private final String workerId = "outlook-discovery-" + UUID.randomUUID();

    public OutlookInboxDiscoveryWorker(JobService jobs, InboxDiscoveryPort inbox, JobDiscoveryProperties properties, MeterRegistry metrics) {
        this.jobs = jobs;
        this.inbox = inbox;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.job.discovery.poll-delay}")
    public void discoverNextJob() {
        jobs.claimNextDiscovery(workerId, properties.leaseDuration()).ifPresent(this::discover);
    }

    void discover(JobService.ClaimedJob job) {
        var nextLink = (String) null;
        Set<String> visitedNextLinks = new HashSet<>();
        var discovered = 0;
        try {
            while (discovered < properties.maxMessagesPerJob()) {
                if (jobs.isCancelled(job.jobId())) {
                    outcome("cancelled");
                    return;
                }
                if (nextLink != null && !visitedNextLinks.add(nextLink)) {
                    throw new InboxDiscoveryException(InboxDiscoveryException.Kind.PROTOCOL_ERROR);
                }
                var page = inbox.listInboxMessages(job.receivedFromUtc(), job.receivedToUtcExclusive(), nextLink, properties.pageSize(),
                        () -> renewLease(job));
                graphRequest("success");
                var pageMessages = new ArrayList<JobService.DiscoveredMessage>();
                for (var message : page.messages()) {
                    if (!message.receivedAt().isBefore(job.receivedFromUtc()) && message.receivedAt().isBefore(job.receivedToUtcExclusive()) && discovered < properties.maxMessagesPerJob()) {
                        pageMessages.add(new JobService.DiscoveredMessage(message.immutableId(), message.receivedAt(), message.hasAttachments()));
                        discovered++;
                    }
                }
                if (!jobs.saveDiscoveredPage(job.jobId(), workerId, pageMessages)) { outcome("cancelled"); return; }
                metrics.counter("outlook.discovery_messages").increment(pageMessages.size());
                if (discovered == properties.maxMessagesPerJob()) { complete(job, "MESSAGE_LIMIT_REACHED", "warning"); return; }
                if (page.nextLink() == null) { complete(job, null, "completed"); return; }
                nextLink = page.nextLink();
            }
        } catch (InboxDiscoveryException exception) {
            switch (exception.kind()) {
                case REAUTHORIZATION_REQUIRED -> { jobs.failDiscovery(job.jobId(), workerId, JobService.Status.REAUTHORIZATION_REQUIRED, "OUTLOOK_REAUTH_REQUIRED"); outcome("reauth_required"); }
                case TEMPORARY_FAILURE -> { graphRequest("transient_failure"); jobs.failDiscovery(job.jobId(), workerId, JobService.Status.FAILED, "OUTLOOK_DISCOVERY_TEMPORARY_FAILURE"); outcome("failed"); }
                case PROTOCOL_ERROR -> { graphRequest("protocol_failure"); jobs.failDiscovery(job.jobId(), workerId, JobService.Status.FAILED, "OUTLOOK_DISCOVERY_PROTOCOL_ERROR"); outcome("failed"); }
                case CANCELLED -> outcome("cancelled");
            }
        }
    }

    private void outcome(String outcome) { metrics.counter("outlook.discovery_jobs", "outcome", outcome).increment(); }
    private void graphRequest(String outcome) { metrics.counter("outlook.graph_requests", "outcome", outcome).increment(); }
    private void renewLease(JobService.ClaimedJob job) {
        if (!jobs.renewLease(job.jobId(), workerId, properties.leaseDuration())) {
            throw new InboxDiscoveryException(jobs.isCancelled(job.jobId())
                    ? InboxDiscoveryException.Kind.CANCELLED : InboxDiscoveryException.Kind.TEMPORARY_FAILURE);
        }
    }
    private void complete(JobService.ClaimedJob job, String warningCode, String successOutcome) {
        if (jobs.completeDiscovery(job.jobId(), workerId, warningCode)) outcome(successOutcome);
        else outcome(jobs.isCancelled(job.jobId()) ? "cancelled" : "failed");
    }
}
