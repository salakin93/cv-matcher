package com.cvmatcher.cv_matcher_backend.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptedDocumentStorageTest {
    @TempDir Path root;
    @Test void storesCiphertextWithoutPlaintext() throws Exception {
        var properties = new DocumentIngestionProperties(true, java.time.Duration.ofSeconds(1), java.time.Duration.ofMinutes(1), 100, 1, 100, 1, root.toString(), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, "clean", "localhost", 3310, java.time.Duration.ofSeconds(1));
        var storage = new EncryptedDocumentStorage(properties); storage.validate();
        var key = storage.store("private cv content".getBytes(StandardCharsets.UTF_8));
        assertFalse(new String(Files.readAllBytes(root.resolve(key)), StandardCharsets.ISO_8859_1).contains("private cv content"));
        storage.delete(key);
        assertFalse(Files.exists(root.resolve(key)));
    }

    @Test void failsWithoutLeavingTemporaryFilesWhenTheStorageRootDisappears() throws Exception {
        var properties = new DocumentIngestionProperties(true, java.time.Duration.ofSeconds(1), java.time.Duration.ofMinutes(1), 100, 1, 100, 1, root.toString(), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, "clean", "localhost", 3310, java.time.Duration.ofSeconds(1));
        var storage = new EncryptedDocumentStorage(properties); storage.validate();
        Files.delete(root);

        assertThrows(IllegalStateException.class, () -> storage.store("synthetic".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Files.exists(root.resolve(".tmp")));
    }
}
