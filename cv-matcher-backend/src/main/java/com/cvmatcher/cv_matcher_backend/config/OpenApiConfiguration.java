package com.cvmatcher.cv_matcher_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

	@Bean
	OpenAPI cvMatcherOpenApi() {
		return new OpenAPI().info(new Info()
			.title("CV Matcher API")
			.version("v1")
			.description("API para la preselección asistida y explicable de candidatos."));
	}
}
