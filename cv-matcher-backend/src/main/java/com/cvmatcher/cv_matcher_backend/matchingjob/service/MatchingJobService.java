package com.cvmatcher.cv_matcher_backend.matchingjob.service;

import com.cvmatcher.cv_matcher_backend.matchingjob.api.CreateMatchingJobRequest;
import com.cvmatcher.cv_matcher_backend.matchingjob.api.MatchingJobCreatedResponse;
import com.cvmatcher.cv_matcher_backend.matchingjob.api.MatchingJobStatusResponse;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJob;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobEvent;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingJobService {

    private static final EnumSet<MatchingJobStatus> ACTIVE_STATUSES = EnumSet.complementOf(
            EnumSet.of(
                    MatchingJobStatus.COMPLETED,
                    MatchingJobStatus.COMPLETED_WITH_WARNINGS,
                    MatchingJobStatus.INGESTION_FAILED,
                    MatchingJobStatus.REAUTHORIZATION_REQUIRED));

    private final MatchingJobRepository matchingJobRepository;
    private final MatchingJobEventRepository matchingJobEventRepository;

    public MatchingJobService(
            MatchingJobRepository matchingJobRepository,
            MatchingJobEventRepository matchingJobEventRepository) {
        this.matchingJobRepository = matchingJobRepository;
        this.matchingJobEventRepository = matchingJobEventRepository;
    }

    @Transactional
    public MatchingJobCreatedResponse create(CreateMatchingJobRequest request, HttpServletRequest httpRequest) {
        validateBusinessRules(request);

        matchingJobRepository.findFirstByStatusInOrderByCreatedAtAsc(ACTIVE_STATUSES)
                .ifPresent(activeJob -> {
                    throw new MatchingJobConflictException(activeJob.getId());
                });

        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = httpRequest.getHeader("X-Correlation-Id");
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MatchingJob job = new MatchingJob(
                request.title().trim(),
                request.description().trim(),
                request.from(),
                request.to(),
                correlationId);
        for (int index = 0; index < request.requirements().size(); index++) {
            var requirement = request.requirements().get(index);
            job.addRequirement(
                    requirement.description().trim(),
                    requirement.weight(),
                    requirement.mandatory(),
                    index);
        }

        try {
            MatchingJob saved = matchingJobRepository.saveAndFlush(job);
            matchingJobEventRepository.save(MatchingJobEvent.created(saved.getId()));
            return new MatchingJobCreatedResponse(
                    saved.getId(),
                    saved.getStatus(),
                    statusUrl(saved.getId()),
                    saved.getCreatedAt());
        } catch (DataIntegrityViolationException exception) {
            throw new MatchingJobConflictException(null);
        }
    }

    @Transactional(readOnly = true)
    public MatchingJobStatusResponse getStatus(UUID jobId) {
        MatchingJob job = matchingJobRepository.findById(jobId)
                .orElseThrow(() -> new MatchingJobNotFoundException(jobId));
        return new MatchingJobStatusResponse(
                job.getId(),
                statusUrl(job.getId()),
                job.getStatus(),
                job.getJobMode(),
                job.getFrom(),
                job.getTo(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                MatchingJobStatusResponse.MatchingJobCounters.empty());
    }

    public void retry(UUID jobId) {
        if (!matchingJobRepository.existsById(jobId)) {
            throw new MatchingJobNotFoundException(jobId);
        }
        throw new InvalidMatchingJobRequestException("Matching job retries are not available until document ingestion is implemented");
    }

    private void validateBusinessRules(CreateMatchingJobRequest request) {
        if (request.from().isAfter(request.to())) {
            throw new InvalidMatchingJobRequestException("from must be before or equal to to");
        }
        if (Duration.between(request.from(), request.to()).compareTo(Duration.ofDays(31)) > 0) {
            throw new InvalidMatchingJobRequestException("The requested date range must not exceed 31 days");
        }
        boolean containsMandatoryRequirement = request.requirements().stream().anyMatch(requirement -> requirement.mandatory());
        if (!containsMandatoryRequirement) {
            throw new InvalidMatchingJobRequestException("At least one mandatory requirement is required");
        }
    }

    private String statusUrl(UUID jobId) {
        return "/api/matching-jobs/%s".formatted(jobId);
    }
}
