package com.cvmatcher.cv_matcher_backend.outlook.application;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.OutlookOAuthClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
final class OutlookAccessTokenService {
    private static final int MAX_REFRESH_ROTATION_ATTEMPTS = 2;
    private final OutlookProperties properties;
    private final OutlookConnectionStore connections;
    private final OutlookOAuthClient oauthClient;
    private final OutlookObservability observability;
    private volatile OutlookAccessTokenPort.AccessToken cachedAccessToken;

    OutlookAccessTokenService(OutlookProperties properties, OutlookConnectionStore connections, OutlookOAuthClient oauthClient, OutlookObservability observability) {
        this.properties = properties;
        this.connections = connections;
        this.oauthClient = oauthClient;
        this.observability = observability;
    }

    synchronized OutlookAccessTokenPort.AccessToken accessToken() {
        var cached = cachedAccessToken;
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) return cached;
        OutlookAccessTokenPort.AccessToken mostRecentAccess = null;
        for (var attempt = 0; attempt < MAX_REFRESH_ROTATION_ATTEMPTS; attempt++) {
            var connection = connections.connection();
            if (connection == null || !"CONNECTED".equals(connection.status()))
                throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "OUTLOOK_REAUTHORIZATION_REQUIRED");
            if (connection.keyVersion() != properties.tokenEncryptionKeyVersion()) {
                cachedAccessToken = null;
                connections.markDecryptionFailed();
                throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "TOKEN_DECRYPTION_FAILED");
            }
            try {
                var token = oauthClient.refreshAccessToken(connections.decrypt(connection));
                var access = new OutlookAccessTokenPort.AccessToken(requiredString(token.accessToken()), Instant.now().plusSeconds(token.expiresInSeconds()));
                mostRecentAccess = access;
                if (!connections.applyRefreshRotation(connection.ciphertext(), token.refreshToken())) continue;
                cachedAccessToken = access;
                observability.tokenRefresh("success");
                return access;
            } catch (OutlookOAuthClient.Failure exception) {
                if (exception.kind() != OutlookOAuthClient.Failure.Kind.INVALID_GRANT) {
                    if (exception.kind() == OutlookOAuthClient.Failure.Kind.TEMPORARILY_UNAVAILABLE) observability.tokenRefresh("transient_failure");
                    throw new OutlookException(exception.kind() == OutlookOAuthClient.Failure.Kind.TEMPORARILY_UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, exception.kind() == OutlookOAuthClient.Failure.Kind.TEMPORARILY_UNAVAILABLE
                            ? "OUTLOOK_TEMPORARILY_UNAVAILABLE" : "OUTLOOK_AUTHORIZATION_FAILED");
                }
                cachedAccessToken = null;
                connections.requireReauthorization(connection);
                observability.tokenRefresh("reauth_required");
                throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "OUTLOOK_REAUTHORIZATION_REQUIRED");
            } catch (OutlookException exception) {
                if ("OUTLOOK_TEMPORARILY_UNAVAILABLE".equals(exception.code())) observability.tokenRefresh("transient_failure");
                throw exception;
            } catch (IllegalArgumentException exception) {
                cachedAccessToken = null;
                connections.markDecryptionFailed();
                observability.tokenRefresh("decryption_failed");
                throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "TOKEN_DECRYPTION_FAILED");
            }
        }
        cachedAccessToken = mostRecentAccess;
        observability.tokenRefresh("success");
        return mostRecentAccess;
    }

    void clearCache() {
        cachedAccessToken = null;
    }

    private static String requiredString(String value) {
        if (value == null || value.isBlank())
            throw new OutlookException(HttpStatus.BAD_GATEWAY, "OUTLOOK_AUTHORIZATION_FAILED");
        return value;
    }
}
