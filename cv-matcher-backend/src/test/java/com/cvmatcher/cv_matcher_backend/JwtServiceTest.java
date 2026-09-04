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
        var properties = new SecurityProperties("too-short", 15, 8, 24, 30, "", "", false);

        assertThrows(IllegalStateException.class, () -> new JwtService(properties, new ObjectMapper()));
    }

    @Test
    void issuesAndVerifiesClaimsForAnUnexpiredAccessToken() {
        var properties = new SecurityProperties("a".repeat(32), 15, 8, 24, 30, "", "", false);
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
        var properties = new SecurityProperties("a".repeat(32), -1, 8, 24, 30, "", "", false);
        var service = new JwtService(properties, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.verify(service.issue(UUID.randomUUID(), "RECRUITER", UUID.randomUUID())));
    }
}
