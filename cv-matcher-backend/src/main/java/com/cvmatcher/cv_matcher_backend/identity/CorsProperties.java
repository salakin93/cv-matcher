package com.cvmatcher.cv_matcher_backend.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
