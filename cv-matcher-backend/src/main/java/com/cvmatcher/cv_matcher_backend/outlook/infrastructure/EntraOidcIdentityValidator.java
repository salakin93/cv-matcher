package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
final class EntraOidcIdentityValidator implements OidcIdentityValidator {
    private final OutlookProperties properties;

    EntraOidcIdentityValidator(OutlookProperties properties) {
        this.properties = properties;
    }

    @Override
    public Identity validate(String idToken, byte[] expectedNonceHash) {
        Jwt jwt = JwtDecoders.fromIssuerLocation(properties.authority() + "/v2.0").decode(idToken);
        if (!jwt.getAudience().contains(properties.clientId()) || !properties.tenantId().equals(jwt.getClaimAsString("tid"))
                || jwt.getClaimAsString("nonce") == null || !MessageDigest.isEqual(expectedNonceHash,
                hash(jwt.getClaimAsString("nonce").getBytes(StandardCharsets.US_ASCII)))) {
            throw new OutlookException(HttpStatus.BAD_GATEWAY, "OUTLOOK_AUTHORIZATION_FAILED");
        }
        return new Identity(jwt.getClaimAsString("tid"), jwt.getSubject());
    }

    private static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
