package com.cvmatcher.cv_matcher_backend.error;

public enum ErrorCode {
	VALIDATION_ERROR("Validation failed"),
	UNAUTHORIZED("Authentication is required"),
	FORBIDDEN("You do not have permission to access this resource"),
	NOT_FOUND("Resource not found"),
	CONFLICT("Request conflicts with the current resource state"),
	EXTERNAL_SERVICE_ERROR("External service is unavailable"),
	INTERNAL_ERROR("Internal server error");

	private final String publicMessage;

	ErrorCode(String publicMessage) {
		this.publicMessage = publicMessage;
	}

	public String publicMessage() {
		return publicMessage;
	}
}
