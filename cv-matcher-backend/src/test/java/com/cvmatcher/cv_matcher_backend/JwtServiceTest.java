package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    @Test
    void rejectsASigningKeyShorterThan256Bits() {
        var properties = new SecurityProperties("too-short", 15, 8, 24, 30, "", "");

        assertThrows(IllegalStateException.class, () -> new JwtService(properties, new ObjectMapper()));
    }
}
