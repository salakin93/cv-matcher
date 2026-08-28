package com.cvmatcher.cv_matcher_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import com.cvmatcher.cv_matcher_backend.config.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CvMatcherBackendApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void healthEndpointIsAvailableAndPropagatesCorrelationId() throws Exception {
		mockMvc.perform(get("/actuator/health").header(CorrelationIdFilter.HEADER_NAME, "6b1f89dd-8c4c-4d35-b4d3-518ea250b945"))
			.andExpect(status().isOk())
			.andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "6b1f89dd-8c4c-4d35-b4d3-518ea250b945"))
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void openApiDocumentIsAvailable() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.title").value("CV Matcher API"));
	}

	@Test
	void protectedApiReturnsNormalizedUnauthorizedResponse() throws Exception {
		mockMvc.perform(get("/api/protected-resource").header(CorrelationIdFilter.HEADER_NAME, "3405d906-b531-4054-954b-173633d09dc8"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.correlationId").value("3405d906-b531-4054-954b-173633d09dc8"));
	}

	@Test
	@WithMockUser
	void deniedRouteReturnsNormalizedForbiddenResponse() throws Exception {
		mockMvc.perform(get("/private-resource").header(CorrelationIdFilter.HEADER_NAME, "f555e276-6166-4a8c-8e5e-b1dac5f0d645"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.correlationId").value("f555e276-6166-4a8c-8e5e-b1dac5f0d645"));
	}

	@Test
	@WithMockUser
	void stateChangingApiRequestRequiresCsrfToken() throws Exception {
		mockMvc.perform(post("/api/protected-resource"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

}
