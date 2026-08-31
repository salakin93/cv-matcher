package com.cvmatcher.cv_matcher_backend.matchingjob.api;

import com.cvmatcher.cv_matcher_backend.matchingjob.service.InvalidMatchingJobRequestException;
import com.cvmatcher.cv_matcher_backend.matchingjob.service.MatchingJobConflictException;
import com.cvmatcher.cv_matcher_backend.matchingjob.service.MatchingJobNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MatchingJobExceptionHandler {

    @ExceptionHandler(MatchingJobConflictException.class)
    ResponseEntity<JobErrorResponse> handleConflict(MatchingJobConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request, exception.getActiveJobId());
    }

    @ExceptionHandler(MatchingJobNotFoundException.class)
    ResponseEntity<JobErrorResponse> handleNotFound(MatchingJobNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request, null);
    }

    @ExceptionHandler({InvalidMatchingJobRequestException.class, MethodArgumentNotValidException.class})
    ResponseEntity<JobErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "The matching job request is invalid", request, null);
    }

    private ResponseEntity<JobErrorResponse> error(HttpStatus status, String message, HttpServletRequest request, UUID activeJobId) {
        return ResponseEntity.status(status).body(new JobErrorResponse(
                status.value(), message, Instant.now(), request.getMethod(), request.getRequestURI(), activeJobId));
    }

    record JobErrorResponse(int status, String message, Instant timestamp, String method, String requestUri, UUID activeJobId) {
    }
}
