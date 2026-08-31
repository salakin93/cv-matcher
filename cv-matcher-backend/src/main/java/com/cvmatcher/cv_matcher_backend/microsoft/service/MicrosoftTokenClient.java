package com.cvmatcher.cv_matcher_backend.microsoft.service;

public interface MicrosoftTokenClient {
    MicrosoftTokenResponse exchangeAuthorizationCode(String code, String codeVerifier);
}
