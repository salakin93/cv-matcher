package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;

public final class AesGcmCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(String encodedKey) {
        this.key = Base64.getDecoder().decode(encodedKey);
        if (key.length != 32) throw new IllegalArgumentException("Invalid encryption key");
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = c.doFinal(plain), out = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] decrypt(byte[] value) {
        try {
            if (value.length < 29) throw new IllegalArgumentException();
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, java.util.Arrays.copyOf(value, 12)));
            return c.doFinal(value, 12, value.length - 12);
        } catch (Exception e) {
            throw new IllegalArgumentException("Ciphertext invalid", e);
        }
    }
}
