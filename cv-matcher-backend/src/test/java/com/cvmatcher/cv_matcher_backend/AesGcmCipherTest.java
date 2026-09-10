package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.AesGcmCipher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmCipherTest {
    private final AesGcmCipher cipher = new AesGcmCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsWithoutPersistingPlaintextAndDecrypts() {
        var plaintext = "refresh-token-value".getBytes(StandardCharsets.UTF_8);
        var ciphertext = cipher.encrypt(plaintext);

        assertFalse(new String(ciphertext, StandardCharsets.ISO_8859_1).contains("refresh-token-value"));
        assertArrayEquals(plaintext, cipher.decrypt(ciphertext));
    }

    @Test
    void rejectsTamperedCiphertext() {
        var ciphertext = cipher.encrypt("refresh-token-value".getBytes(StandardCharsets.UTF_8));
        ciphertext[ciphertext.length - 1] ^= 1;

        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(ciphertext));
    }
}
