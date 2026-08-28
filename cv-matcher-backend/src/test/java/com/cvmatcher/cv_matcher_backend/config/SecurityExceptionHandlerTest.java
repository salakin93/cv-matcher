package com.cvmatcher.cv_matcher_backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class SecurityExceptionHandlerTest {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final SecurityErrorResponseWriter responseWriter = new SecurityErrorResponseWriter(objectMapper);

	@Test
	void authenticationEntryPointReturnsNormalizedUnauthorizedResponse() throws Exception {
		RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(responseWriter);
		MockHttpServletRequest request = request();
		MockHttpServletResponse response = new MockHttpServletResponse();

		entryPoint.commence(request, response, new InsufficientAuthenticationException("credentials missing"));

		assertSecurityError(response, 401, "UNAUTHORIZED", "Authentication is required");
	}

	@Test
	void accessDeniedHandlerReturnsNormalizedForbiddenResponse() throws Exception {
		RestAccessDeniedHandler deniedHandler = new RestAccessDeniedHandler(responseWriter);
		MockHttpServletRequest request = request();
		MockHttpServletResponse response = new MockHttpServletResponse();

		deniedHandler.handle(request, response, new AccessDeniedException("sensitive authorization detail"));

		assertSecurityError(response, 403, "FORBIDDEN", "You do not have permission to access this resource");
	}

	private MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/protected-resource");
		request.setAttribute(CorrelationIdFilter.ATTRIBUTE_NAME, "82d283be-fd82-4e5f-9ab4-e509e03ec993");
		return request;
	}

	private void assertSecurityError(MockHttpServletResponse response, int status, String code, String message) throws Exception {
		JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(body.path("status").asInt()).isEqualTo(status);
		assertThat(body.path("code").asText()).isEqualTo(code);
		assertThat(body.path("message").asText()).isEqualTo(message);
		assertThat(body.path("requestUri").asText()).isEqualTo("/api/protected-resource");
		assertThat(body.path("correlationId").asText()).isEqualTo("82d283be-fd82-4e5f-9ab4-e509e03ec993");
	}
}
