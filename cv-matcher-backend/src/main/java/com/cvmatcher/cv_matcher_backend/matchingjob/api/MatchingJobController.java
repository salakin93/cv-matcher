package com.cvmatcher.cv_matcher_backend.matchingjob.api;

import com.cvmatcher.cv_matcher_backend.matchingjob.service.MatchingJobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/matching-jobs")
public class MatchingJobController {

    private final MatchingJobService matchingJobService;

    public MatchingJobController(MatchingJobService matchingJobService) {
        this.matchingJobService = matchingJobService;
    }

    @PostMapping
    public ResponseEntity<MatchingJobCreatedResponse> create(
            @Valid @RequestBody CreateMatchingJobRequest request,
            HttpServletRequest httpRequest) {
        MatchingJobCreatedResponse response = matchingJobService.create(request, httpRequest);
        return ResponseEntity.accepted()
                .location(URI.create(response.statusUrl()))
                .body(response);
    }

    @GetMapping("/{jobId}")
    public MatchingJobStatusResponse getStatus(@PathVariable UUID jobId) {
        return matchingJobService.getStatus(jobId);
    }

    @PostMapping("/{jobId}/retry")
    public void retry(@PathVariable UUID jobId) {
        matchingJobService.retry(jobId);
    }
}
