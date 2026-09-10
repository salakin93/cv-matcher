package com.cvmatcher.cv_matcher_backend.outlook.application;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.OidcIdentityValidator;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.OutlookOAuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class OutlookService implements OutlookAccessTokenPort {
    private static final Logger log = LoggerFactory.getLogger(OutlookService.class);
    private static final List<String> REQUIRED_SCOPES = List.of("openid", "profile", "offline_access");
    private final OutlookProperties properties;
    private final OutlookAuthorizationAttempts attempts;
    private final OutlookConnectionStore connections;
    private final OutlookAccessTokenService accessTokens;
    private final OutlookOAuthClient oauthClient;
    private final OidcIdentityValidator identityValidator;
    private final OutlookObservability observability;

    public OutlookService(OutlookProperties properties, OutlookAuthorizationAttempts attempts, OutlookConnectionStore connections,
                           OutlookAccessTokenService accessTokens, OutlookOAuthClient oauthClient,
                          OidcIdentityValidator identityValidator, OutlookObservability observability) {
        this.properties = properties;
        this.attempts = attempts;
        this.connections = connections;
        this.accessTokens = accessTokens;
        this.oauthClient = oauthClient;
        this.identityValidator = identityValidator;
        this.observability = observability;
    }

    public record Status(String status, Instant connectedAt, Instant lastTokenRefreshAt, List<String> grantedScopes,
                         String lastErrorCode, Instant updatedAt) {
    }

    public record Start(String authorizationUrl, Instant expiresAt, String status) {
    }

    public Status status() {
        return connections.status();
    }

    public Start start(UUID actor) {
        configured();
        return attempts.start(actor);
    }

    public String completeAuthorization(String state, String code, String error) {
        configured();
        if (error != null) {
            attempts.consume(state);
            observability.authorization("denied");
            return redirect("denied");
        }
        if (blank(code)) {
            attempts.consume(state);
            observability.authorization("failed");
            return redirect("error");
        }
        var attempt = attempts.consume(state);
        try {
            var token = oauthClient.exchangeAuthorizationCode(code, attempts.decryptVerifier(attempt));
            var identity = identityValidator.validate(requiredString(token.idToken()), attempt.nonceHash());
            var refresh = requiredString(token.refreshToken());
            var grantedScopes = scopes(token.scope());
            if (!onlyRequestedScopes(grantedScopes))
                throw new OutlookException(HttpStatus.BAD_GATEWAY, "OUTLOOK_AUTHORIZATION_FAILED");
            accessTokens.clearCache();
            connections.connect(refresh, identity.tenantId(), identity.subject(), grantedScopes, attempt.actor());
            observability.authorization("success");
            return redirect("connected");
        } catch (OutlookOAuthClient.Failure exception) {
            accessTokens.clearCache();
            if (exception.kind() == OutlookOAuthClient.Failure.Kind.INVALID_GRANT) connections.requireReauthorization();
            observability.authorization("failed");
            logFailure(errorCode(exception));
            return redirect("error");
        } catch (OutlookException exception) {
            observability.authorization("failed");
            logFailure(exception.code());
            return redirect("error");
        } catch (Exception exception) {
            observability.authorization("failed");
            logFailure("OUTLOOK_AUTHORIZATION_FAILED");
            return redirect("error");
        }
    }

    @Override
    public AccessToken accessToken() {
        return accessTokens.accessToken();
    }

    private void configured() {
        if (blank(properties.tenantId()) || blank(properties.clientId()) || blank(properties.clientSecret()) || blank(properties.authority()) || blank(properties.redirectUri()) || blank(properties.tokenEncryptionKey()) || properties.tokenEncryptionKeyVersion() <= 0 || !validBaseUrl(properties.appBaseUrl()))
            throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "OUTLOOK_NOT_CONFIGURED");
    }

    private String redirect(String result) {
        return UriComponentsBuilder.fromUriString(properties.appBaseUrl()).path("/admin/integrations/outlook/callback").queryParam("result", result).build().encode().toUriString();
    }

    private void logFailure(String code) {
        log.warn("outlook_authorization_failed code={} correlationId={}", code, observability.correlationId());
    }

    private static String requiredString(String value) {
        if (value == null || value.isBlank())
            throw new OutlookException(HttpStatus.BAD_GATEWAY, "OUTLOOK_AUTHORIZATION_FAILED");
        return value;
    }

    private static String[] scopes(String value) {
        return value == null ? new String[0] : Arrays.stream(value.trim().split("\\s+")).filter(scope -> !scope.isBlank()).distinct().sorted().toArray(String[]::new);
    }

    private static boolean onlyRequestedScopes(String[] grantedScopes) {
        return grantedScopes.length == REQUIRED_SCOPES.size() && Arrays.stream(grantedScopes).allMatch(REQUIRED_SCOPES::contains);
    }

    private static String errorCode(OutlookOAuthClient.Failure failure) {
        return failure.kind() == OutlookOAuthClient.Failure.Kind.TEMPORARILY_UNAVAILABLE
                ? "OUTLOOK_TEMPORARILY_UNAVAILABLE" : "OUTLOOK_AUTHORIZATION_FAILED";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean validBaseUrl(String value) {
        try {
            var uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
