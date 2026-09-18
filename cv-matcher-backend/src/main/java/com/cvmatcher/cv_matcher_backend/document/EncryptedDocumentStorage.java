package com.cvmatcher.cv_matcher_backend.document;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.job.documents", name = "enabled", havingValue = "true")
final class EncryptedDocumentStorage {
    private final DocumentIngestionProperties properties;
    private Path root;
    EncryptedDocumentStorage(DocumentIngestionProperties properties) { this.properties = properties; }
    @PostConstruct void validate() {
        try {
            if (properties.storageRoot() == null || properties.storageRoot().isBlank()) throw new IllegalStateException("document storage is not configured");
            root = Path.of(properties.storageRoot()).toAbsolutePath().normalize();
            if (root.toString().contains("static") || root.toString().contains("templates")) throw new IllegalStateException("document storage must be private");
            Files.createDirectories(root);
            if (!Files.isDirectory(root) || !Files.isWritable(root) || Base64.getDecoder().decode(properties.encryptionKey()).length != 32) throw new IllegalStateException("document storage is unavailable");
        } catch (Exception exception) { throw new IllegalStateException("document storage is unavailable"); }
    }
    String store(byte[] plaintext) {
        var key = Base64.getDecoder().decode(properties.encryptionKey());
        var nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        var storageKey = UUID.randomUUID() + ".gcm";
        var temporary = root.resolve(storageKey + ".tmp"); var target = root.resolve(storageKey);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            var encrypted = cipher.doFinal(plaintext); var output = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, output, 0, nonce.length); System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            Files.write(temporary, output); Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return storageKey;
        } catch (Exception exception) { try { Files.deleteIfExists(temporary); Files.deleteIfExists(target); } catch (Exception ignored) {} throw new IllegalStateException("document storage failed"); }
    }
    void delete(String storageKey) {
        try { Files.deleteIfExists(root.resolve(storageKey).normalize()); } catch (Exception ignored) { }
    }
}
