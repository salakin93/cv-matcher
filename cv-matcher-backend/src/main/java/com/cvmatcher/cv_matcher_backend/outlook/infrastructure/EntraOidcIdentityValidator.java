package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
final class EntraOidcIdentityValidator implements OidcIdentityValidator {
    private final OutlookProperties properties;
    private volatile NimbusJwtDecoder decoder;

    EntraOidcIdentityValidator(OutlookProperties properties) {
        this.properties = properties;
    }

    @Override
    public Identity validate(String idToken, byte[] expectedNonceHash) {
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (ResourceAccessException exception) {
            throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "OUTLOOK_TEMPORARILY_UNAVAILABLE");
        } catch (JwtException exception) {
            if (causedByResourceAccess(exception))
                throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "OUTLOOK_TEMPORARILY_UNAVAILABLE");
            throw exception;
        }
        var subject = jwt.getSubject();
        if (!jwt.getAudience().contains(properties.clientId()) || !properties.tenantId().equals(jwt.getClaimAsString("tid"))
                || jwt.getClaimAsString("nonce") == null || !MessageDigest.isEqual(expectedNonceHash,
                hash(jwt.getClaimAsString("nonce").getBytes(StandardCharsets.US_ASCII)))
                || subject == null || subject.isBlank()) {
            throw new OutlookException(HttpStatus.BAD_GATEWAY, "OUTLOOK_AUTHORIZATION_FAILED");
        }
        return new Identity(jwt.getClaimAsString("tid"), subject);
    }

    private NimbusJwtDecoder decoder() {
        var current = decoder;
        if (current != null) return current;
        synchronized (this) {
            if (decoder == null) decoder = discoverDecoder();
            return decoder;
        }
    }

    private NimbusJwtDecoder discoverDecoder() {
        var issuer = properties.authority() + "/v2.0";
        var factory = requestFactory();
        try {
            var discovery = RestClient.builder().requestFactory(factory).build().get()
                    .uri(issuer + "/.well-known/openid-configuration").retrieve().body(DiscoveryDocument.class);
            if (discovery == null || !issuer.equals(discovery.issuer()) || discovery.jwksUri() == null || discovery.jwksUri().isBlank())
                throw new OutlookException(HttpStatus.BAD_GATEWAY, "OUTLOOK_AUTHORIZATION_FAILED");
            var decoder = NimbusJwtDecoder.withJwkSetUri(discovery.jwksUri())
                    .restOperations(new RestTemplate(requestFactory())).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            return decoder;
        } catch (ResourceAccessException exception) {
            throw new OutlookException(HttpStatus.SERVICE_UNAVAILABLE, "OUTLOOK_TEMPORARILY_UNAVAILABLE");
        } catch (OutlookException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OutlookException(HttpStatus.BAD_GATEWAY, "OUTLOOK_AUTHORIZATION_FAILED");
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    private static boolean causedByResourceAccess(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException || cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    private record DiscoveryDocument(String issuer, @tools.jackson.annotation.JsonProperty("jwks_uri") String jwksUri) {
    }

    private static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
