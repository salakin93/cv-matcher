package com.cvmatcher.cv_matcher_backend.matchingjob.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RequirementRequest(
        @NotBlank @Size(max = 2000) String description,
        @NotNull @Min(1) @Max(5) Integer weight,
        @NotNull Boolean mandatory) {
}
