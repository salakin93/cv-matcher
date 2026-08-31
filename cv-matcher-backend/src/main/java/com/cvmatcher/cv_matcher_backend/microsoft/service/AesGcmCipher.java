package com.cvmatcher.cv_matcher_backend.microsoft.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesGcmCipher {

    private static final int NONCE_LENGTH = 12;
    private final String keyMaterial;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCipher(@Value("${OAUTH_TOKEN_ENCRYPTION_KEY:}") String keyMaterial) {
        this.keyMaterial = keyMaterial;
    }

    public EncryptedValue encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new EncryptedValue(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OAuth token encryption is unavailable", exception);
        }
    }

    public String decrypt(byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OAuth token decryption is unavailable", exception);
        }
    }

    private SecretKeySpec key() {
        byte[] decoded = Base64.getDecoder().decode(keyMaterial);
        if (decoded.length != 32) {
            throw new IllegalStateException("OAUTH_TOKEN_ENCRYPTION_KEY must decode to 32 bytes");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    public record EncryptedValue(byte[] ciphertext, byte[] nonce) {}
}
