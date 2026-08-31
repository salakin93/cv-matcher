package com.cvmatcher.cv_matcher_backend.matchingjob.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CreateMatchingJobRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10000) String description,
        @NotEmpty @Size(max = 50) List<@Valid RequirementRequest> requirements,
        @NotNull Instant from,
        @NotNull Instant to) {
}
