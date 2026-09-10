package com.cvmatcher.cv_matcher_backend.outlook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.outlook")
public record OutlookProperties(String tenantId, String clientId, String clientSecret, String authority,
                                String redirectUri,
                                String appBaseUrl, String tokenEncryptionKey, int tokenEncryptionKeyVersion,
                                Duration connectTimeout, Duration readTimeout, int maxRetries) {
}
