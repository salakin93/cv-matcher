package com.cvmatcher.cv_matcher_backend.outlook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.outlook")
public record OutlookProperties(String tenantId, String clientId, String clientSecret, String authority,
                                String redirectUri,
                                String appBaseUrl, String tokenEncryptionKey, int tokenEncryptionKeyVersion,
                                 Duration connectTimeout, Duration readTimeout, int maxRetries, String graphBaseUri,
                                 Duration maxRetryAfter) {
    public static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofSeconds(30);
    public static final Duration MAXIMUM_MAX_RETRY_AFTER = Duration.ofMinutes(5);

    public OutlookProperties(String tenantId, String clientId, String clientSecret, String authority,
                             String redirectUri, String appBaseUrl, String tokenEncryptionKey, int tokenEncryptionKeyVersion,
                             Duration connectTimeout, Duration readTimeout, int maxRetries, String graphBaseUri) {
        this(tenantId, clientId, clientSecret, authority, redirectUri, appBaseUrl, tokenEncryptionKey,
                tokenEncryptionKeyVersion, connectTimeout, readTimeout, maxRetries, graphBaseUri,
                DEFAULT_MAX_RETRY_AFTER);
    }
}
