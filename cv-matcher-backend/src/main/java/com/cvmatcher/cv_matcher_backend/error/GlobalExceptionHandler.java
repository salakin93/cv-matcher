package com.cvmatcher.cv_matcher_backend.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.cvmatcher.cv_matcher_backend.config.CorrelationIdFilter;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
		return response(exception.getStatus(), exception.getErrorCode(), exception.getErrorCode().publicMessage(), request);
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class})
	public ResponseEntity<ApiErrorResponse> handleValidationException(Exception exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.publicMessage(), request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception, HttpServletRequest request) {
		LOGGER.error("Unhandled exception type={} correlationId={}", exception.getClass().getSimpleName(), correlationId(request));
		return response(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.publicMessage(), request);
	}

	private ResponseEntity<ApiErrorResponse> response(HttpStatus status, ErrorCode errorCode, String message,
			HttpServletRequest request) {
		ApiErrorResponse body = new ApiErrorResponse(
			status.value(),
			errorCode.name(),
			message,
			java.time.Instant.now(),
			request.getMethod(),
			request.getRequestURI(),
			correlationId(request)
		);
		return ResponseEntity.status(status).body(body);
	}

	private String correlationId(HttpServletRequest request) {
		Object correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
		return correlationId == null ? null : correlationId.toString();
	}
}
