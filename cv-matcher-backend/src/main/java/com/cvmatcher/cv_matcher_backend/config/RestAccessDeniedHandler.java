package com.cvmatcher.cv_matcher_backend.config;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import com.cvmatcher.cv_matcher_backend.error.ErrorCode;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final SecurityErrorResponseWriter responseWriter;

	public RestAccessDeniedHandler(SecurityErrorResponseWriter responseWriter) {
		this.responseWriter = responseWriter;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException, ServletException {
		responseWriter.write(request, response, HttpStatus.FORBIDDEN.value(), ErrorCode.FORBIDDEN);
	}
}
