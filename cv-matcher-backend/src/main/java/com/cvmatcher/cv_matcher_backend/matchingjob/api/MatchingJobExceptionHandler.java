package com.cvmatcher.cv_matcher_backend.matchingjob.api;

import com.cvmatcher.cv_matcher_backend.matchingjob.service.InvalidMatchingJobRequestException;
import com.cvmatcher.cv_matcher_backend.matchingjob.service.MatchingJobConflictException;
import com.cvmatcher.cv_matcher_backend.matchingjob.service.MatchingJobNotFoundException;
import com.cvmatcher.cv_matcher_backend.matchingjob.service.MicrosoftConnectionRequiredException;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MatchingJobExceptionHandler {

    private static final EnumSet<MatchingJobStatus> ACTIVE_STATUSES = EnumSet.complementOf(
            EnumSet.of(
                    MatchingJobStatus.COMPLETED,
                    MatchingJobStatus.COMPLETED_WITH_WARNINGS,
                    MatchingJobStatus.INGESTION_FAILED,
                    MatchingJobStatus.REAUTHORIZATION_REQUIRED));

    private final MatchingJobRepository matchingJobRepository;

    public MatchingJobExceptionHandler(MatchingJobRepository matchingJobRepository) {
        this.matchingJobRepository = matchingJobRepository;
    }

    @ExceptionHandler(MatchingJobConflictException.class)
    ResponseEntity<JobErrorResponse> handleConflict(MatchingJobConflictException exception, HttpServletRequest request) {
        UUID activeJobId = exception.getActiveJobId();
        if (activeJobId == null) {
            activeJobId = matchingJobRepository.findFirstByStatusInOrderByCreatedAtAsc(ACTIVE_STATUSES)
                    .map(job -> job.getId())
                    .orElse(null);
        }
        return error(HttpStatus.CONFLICT, exception.getMessage(), request, activeJobId);
    }

    @ExceptionHandler(MatchingJobNotFoundException.class)
    ResponseEntity<JobErrorResponse> handleNotFound(MatchingJobNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request, null);
    }

    @ExceptionHandler({InvalidMatchingJobRequestException.class, MethodArgumentNotValidException.class})
    ResponseEntity<JobErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "The matching job request is invalid", request, null);
    }

    @ExceptionHandler(MicrosoftConnectionRequiredException.class)
    ResponseEntity<JobErrorResponse> handleMicrosoftConnectionRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new JobErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Microsoft connection is required",
                Instant.now(),
                request.getMethod(),
                request.getRequestURI(),
                null,
                null,
                "MICROSOFT_CONNECTION_REQUIRED",
                "/api/integrations/microsoft/authorize"));
    }

    private ResponseEntity<JobErrorResponse> error(HttpStatus status, String message, HttpServletRequest request, UUID activeJobId) {
        String statusUrl = activeJobId == null ? null : "/api/matching-jobs/%s".formatted(activeJobId);
        return ResponseEntity.status(status).body(new JobErrorResponse(
                status.value(), message, Instant.now(), request.getMethod(), request.getRequestURI(), activeJobId, statusUrl, null, null));
    }

    record JobErrorResponse(
            int status,
            String message,
            Instant timestamp,
            String method,
            String requestUri,
            UUID activeJobId,
            String statusUrl,
            String code,
            String authorizationUrl) {
    }
}
