package com.cvmatcher.cv_matcher_backend.document;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production cannot silently accept the test AV double. */
@Component
@Profile("prod")
final class DocumentProductionConfigurationValidator {
    private final DocumentIngestionProperties properties;
    DocumentProductionConfigurationValidator(DocumentIngestionProperties properties) { this.properties = properties; }
    @PostConstruct void validate() {
        if (!"clamav".equals(properties.antivirusMode())) throw new IllegalStateException("production antivirus is not configured");
    }
}
