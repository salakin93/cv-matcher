package com.cvmatcher.cv_matcher_backend.identity.application;

import java.util.UUID;

/**
 * Internal identity boundary for invalidating every session owned by an account.
 * Implementations join an existing business transaction when one is active.
 */
public interface SessionRevocationPort {
    void revokeAllSessions(UUID userId);
}
