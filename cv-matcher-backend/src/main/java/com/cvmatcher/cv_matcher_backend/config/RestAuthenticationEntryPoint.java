package com.cvmatcher.cv_matcher_backend.config;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import com.cvmatcher.cv_matcher_backend.error.ErrorCode;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final SecurityErrorResponseWriter responseWriter;

	public RestAuthenticationEntryPoint(SecurityErrorResponseWriter responseWriter) {
		this.responseWriter = responseWriter;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException, ServletException {
		responseWriter.write(request, response, HttpStatus.UNAUTHORIZED.value(), ErrorCode.UNAUTHORIZED);
	}
}
