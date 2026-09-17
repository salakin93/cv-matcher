package com.cvmatcher.cv_matcher_backend.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.job.discovery")
public record JobDiscoveryProperties(boolean enabled, Duration pollDelay, Duration leaseDuration,
                                     Duration connectTimeout, Duration readTimeout, int maxRetries,
                                     int pageSize, int maxMessagesPerJob) {
    public JobDiscoveryProperties {
        if (pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()
                || leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || leaseDuration.compareTo(readTimeout) <= 0
                || pageSize < 1 || pageSize > 50 || maxMessagesPerJob < 1 || maxRetries < 1 || maxRetries > 3) {
            throw new IllegalArgumentException("invalid discovery configuration");
        }
    }
}
