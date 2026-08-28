package com.cvmatcher.cv_matcher_backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

	@Test
	void reusesValidCorrelationIdAndClearsMdc() throws Exception {
		CorrelationIdFilter filter = new CorrelationIdFilter();
		HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);
		FilterChain filterChain = org.mockito.Mockito.mock(FilterChain.class);
		when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("dd44db7a-0f3e-4f8a-b24a-9c20054c4ef3");

		filter.doFilter(request, response, filterChain);

		verify(request).setAttribute(CorrelationIdFilter.ATTRIBUTE_NAME, "dd44db7a-0f3e-4f8a-b24a-9c20054c4ef3");
		verify(response).setHeader(CorrelationIdFilter.HEADER_NAME, "dd44db7a-0f3e-4f8a-b24a-9c20054c4ef3");
		verify(filterChain).doFilter(request, response);
		assertThat(org.slf4j.MDC.get("correlationId")).isNull();
	}
}
