package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

public interface OidcIdentityValidator {
    Identity validate(String idToken, byte[] expectedNonceHash);

    record Identity(String tenantId, String subject) {
    }
}
