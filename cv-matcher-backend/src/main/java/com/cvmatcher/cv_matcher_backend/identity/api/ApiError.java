package com.cvmatcher.cv_matcher_backend.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "ApiError", description = "Error seguro devuelto por la API")
public record ApiError(
        @Schema(example = "400") int status,
        @Schema(example = "VALIDATION_ERROR") String code,
        @Schema(example = "La solicitud no es válida.") String message,
        @Schema(example = "2026-09-03T17:40:41Z") Instant timestamp,
        @Schema(example = "/api/v1/auth/register") String path
) {
}
