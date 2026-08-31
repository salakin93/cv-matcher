package com.cvmatcher.cv_matcher_backend.matchingjob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cvmatcher.cv_matcher_backend.matchingjob.api.CreateMatchingJobRequest;
import com.cvmatcher.cv_matcher_backend.matchingjob.api.RequirementRequest;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJob;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingJobServiceTest {

    @Test
    void createsQueuedJobForValidRequest() {
        MatchingJobRepository repository = mock(MatchingJobRepository.class);
        when(repository.existsByStatusIn(anyCollection())).thenReturn(false);
        when(repository.saveAndFlush(any(MatchingJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getHeader("X-Correlation-Id")).thenReturn("9d9b7b58-0d3b-43f2-9b76-61b678d272ee");

        var response = new MatchingJobService(repository).create(validRequest(), httpRequest);

        assertThat(response.status()).isEqualTo(MatchingJobStatus.QUEUED);
        assertThat(response.statusUrl()).endsWith(response.jobId().toString());
    }

    @Test
    void rejectsRequestWithoutMandatoryRequirement() {
        MatchingJobRepository repository = mock(MatchingJobRepository.class);
        var request = new CreateMatchingJobRequest(
                "Role", "Description", List.of(new RequirementRequest("Optional", 1, false)),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"));

        assertThatThrownBy(() -> new MatchingJobService(repository).create(request, mock(HttpServletRequest.class)))
                .isInstanceOf(InvalidMatchingJobRequestException.class);
    }

    private CreateMatchingJobRequest validRequest() {
        return new CreateMatchingJobRequest(
                "Inspector", "Vacancy description", List.of(new RequirementRequest("Requirement", 5, true)),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"));
    }
}
