package com.cvmatcher.cv_matcher_backend.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record SecurityProperties(String jwtSigningKey, long accessTokenMinutes, long sessionHours,
                                  long verificationHours, boolean secureCookies, long verificationResendLimit,
                                  long verificationResendWindowSeconds, String outboxEncryptionKey,
                                  int outboxEncryptionKeyVersion, long loginMaxFailedAttempts,
                                  long loginLockMinutes) {
}
