package com.cvmatcher.cv_matcher_backend.outlook.application;

import java.time.Instant;

public interface OutlookAccessTokenPort {
    AccessToken accessToken();

    record AccessToken(String value, Instant expiresAt) {
    }
}
