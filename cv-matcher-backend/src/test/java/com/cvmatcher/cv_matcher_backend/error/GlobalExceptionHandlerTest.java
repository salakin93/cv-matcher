package com.cvmatcher.cv_matcher_backend.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.cvmatcher.cv_matcher_backend.config.CorrelationIdFilter;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void returnsNormalizedErrorWithoutTechnicalDetails() {
		HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/api/test");
		when(request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME)).thenReturn("test-correlation-id");

		ResponseEntity<ApiErrorResponse> response = handler.handleApiException(
			new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.EXTERNAL_SERVICE_ERROR, "Upstream response includes confidential data"), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("EXTERNAL_SERVICE_ERROR");
		assertThat(response.getBody().message()).isEqualTo("External service is unavailable");
		assertThat(response.getBody().message()).doesNotContain("confidential");
		assertThat(response.getBody().correlationId()).isEqualTo("test-correlation-id");
		assertThat(response.getBody().requestUri()).isEqualTo("/api/test");
	}

	@Test
	void returnsBadRequestForValidationErrors() {
		HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/api/test");

		ResponseEntity<ApiErrorResponse> response = handler.handleValidationException(
			new jakarta.validation.ValidationException("invalid confidential input"), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
		assertThat(response.getBody().message()).isEqualTo("Validation failed");
	}
}
