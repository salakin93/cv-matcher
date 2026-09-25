package com.cvmatcher.cv_matcher_backend.identity.application;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class VerificationOutbox {
    private final JdbcTemplate jdbc;
    private final SecurityProperties properties;
    private final byte[] encryptionKey;

    public VerificationOutbox(JdbcTemplate jdbc, SecurityProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.encryptionKey = decodeEncryptionKey(properties);
    }

    public void enqueue(String recipient, String purpose, String payload) {
        jdbc.update(
                "insert into outbox_message(id,recipient,purpose,payload_ciphertext,encryption_key_version,created_at) values(?,?,?,?,?,?)",
                UUID.randomUUID(), recipient, purpose, encrypt(payload), properties.outboxEncryptionKeyVersion(), Timestamp.from(Instant.now())
        );
    }

    private byte[] encrypt(String token) {
        try {
            var nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            var encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            var payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return payload;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt verification outbox payload", exception);
        }
    }

    private static byte[] decodeEncryptionKey(SecurityProperties properties) {
        if (properties.outboxEncryptionKeyVersion() <= 0) {
            throw new IllegalStateException("Invalid verification outbox encryption key configuration");
        }
        if (properties.outboxEncryptionKey() == null || properties.outboxEncryptionKey().isBlank()) {
            throw new IllegalStateException("Invalid verification outbox encryption key configuration");
        }
        try {
            var key = Base64.getDecoder().decode(properties.outboxEncryptionKey());
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalStateException("Invalid verification outbox encryption key configuration");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid verification outbox encryption key configuration");
        }
    }
}
