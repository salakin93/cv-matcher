package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

public interface OutlookOAuthClient {
    String authorizationUrl(String state, String nonce, String codeChallenge);

    TokenResponse exchangeAuthorizationCode(String code, String codeVerifier);

    TokenResponse refreshAccessToken(String refreshToken);

    record TokenResponse(String accessToken, String refreshToken, String idToken, String scope, long expiresInSeconds) {
    }

    final class Failure extends RuntimeException {
        public enum Kind { INVALID_GRANT, AUTHORIZATION_FAILED, TEMPORARILY_UNAVAILABLE }

        private final Kind kind;

        public Failure(Kind kind) {
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
