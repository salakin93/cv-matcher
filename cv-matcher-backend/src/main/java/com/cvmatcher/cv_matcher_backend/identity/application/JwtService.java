package com.cvmatcher.cv_matcher_backend.identity.application;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    public JwtService(SecurityProperties properties, ObjectMapper objectMapper) {
        if (properties.jwtSigningKey() == null || properties.jwtSigningKey().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT signing key must contain at least 256 bits of entropy");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String issue(UUID userId, String role, UUID sessionId) {
        try {
            var now = Instant.now();
            var header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            var payload = encode(objectMapper.writeValueAsString(Map.of("sub", userId.toString(), "role", role, "sid", sessionId.toString(), "iat", now.getEpochSecond(), "exp", now.plusSeconds(properties.accessTokenMinutes() * 60).getEpochSecond())));
            return header + "." + payload + "." + sign(header + "." + payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to issue access token", exception);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String token) {
        try {
            var parts = token.split("\\.");
            if (parts.length != 3 || !constantTime(parts[2], sign(parts[0] + "." + parts[1])))
                throw new IllegalArgumentException("Invalid token");
            var payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            if (((Number) payload.get("exp")).longValue() < Instant.now().getEpochSecond())
                throw new IllegalArgumentException("Expired token");
            return payload;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid token", exception);
        }
    }

    private String sign(String value) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.jwtSigningKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean constantTime(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
