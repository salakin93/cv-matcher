package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    @Test
    void rejectsASigningKeyShorterThan256Bits() {
        var properties = properties("too-short", 15);

        assertThrows(IllegalStateException.class, () -> new JwtService(properties, new ObjectMapper()));
    }

    @Test
    void issuesAndVerifiesClaimsForAnUnexpiredAccessToken() {
        var properties = properties("a".repeat(32), 15);
        var service = new JwtService(properties, new ObjectMapper());
        var userId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();

        var claims = service.verify(service.issue(userId, "RECRUITER", sessionId));

        assertEquals(userId.toString(), claims.get("sub"));
        assertEquals("RECRUITER", claims.get("role"));
        assertEquals(sessionId.toString(), claims.get("sid"));
    }

    @Test
    void rejectsExpiredAccessTokens() {
        var properties = properties("a".repeat(32), -1);
        var service = new JwtService(properties, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.verify(service.issue(UUID.randomUUID(), "RECRUITER", UUID.randomUUID())));
    }

    @Test
    void rejectsAnAccessTokenAtItsExactExpirationSecond() {
        var properties = properties("a".repeat(32), 0);
        var service = new JwtService(properties, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.verify(service.issue(UUID.randomUUID(), "RECRUITER", UUID.randomUUID())));
    }

    private SecurityProperties properties(String signingKey, long accessTokenMinutes) {
        return new SecurityProperties(signingKey, accessTokenMinutes, 8, 24, false, 3, 3600, "", 1, 5, 15,
                30, 3, 3600, 24, 3, 3600, "", "");
    }
}
