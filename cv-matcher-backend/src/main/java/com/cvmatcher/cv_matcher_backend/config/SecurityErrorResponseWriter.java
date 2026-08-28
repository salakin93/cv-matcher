package com.cvmatcher.cv_matcher_backend.config;

import java.io.IOException;
import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import com.cvmatcher.cv_matcher_backend.error.ApiErrorResponse;
import com.cvmatcher.cv_matcher_backend.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorResponseWriter {

	private final ObjectMapper objectMapper;

	public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void write(HttpServletRequest request, HttpServletResponse response, int status, ErrorCode errorCode)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
			status,
			errorCode.name(),
			errorCode.publicMessage(),
			Instant.now(),
			request.getMethod(),
			request.getRequestURI(),
			correlationId(request)
		));
	}

	private String correlationId(HttpServletRequest request) {
		Object correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
		return correlationId == null ? null : correlationId.toString();
	}
}
