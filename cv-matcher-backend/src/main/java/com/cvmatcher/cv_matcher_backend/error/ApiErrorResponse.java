package com.cvmatcher.cv_matcher_backend.error;

import java.time.Instant;

public record ApiErrorResponse(
	int status,
	String code,
	String message,
	Instant timestamp,
	String method,
	String requestUri,
	String correlationId
) {
}
