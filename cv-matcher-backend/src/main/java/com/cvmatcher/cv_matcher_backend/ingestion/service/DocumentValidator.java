package com.cvmatcher.cv_matcher_backend.ingestion.service;

import com.cvmatcher.cv_matcher_backend.ingestion.domain.IgnoredDocumentReason;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphClient.GraphAttachment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
public class DocumentValidator {
    static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    public ValidationResult validate(GraphAttachment attachment) {
        byte[] content = attachment.content();
        if (attachment.inline()) return ValidationResult.ignored(IgnoredDocumentReason.UNSUPPORTED_FORMAT);
        if (content == null || content.length == 0) return ValidationResult.ignored(IgnoredDocumentReason.EMPTY_FILE);
        if (content.length > MAX_FILE_SIZE) return ValidationResult.ignored(IgnoredDocumentReason.OVERSIZED);
        String name = attachment.name() == null ? "" : attachment.name().toLowerCase(Locale.ROOT);
        boolean pdf = name.endsWith(".pdf") && "application/pdf".equalsIgnoreCase(attachment.contentType())
                && startsWith(content, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        boolean docx = name.endsWith(".docx")
                && "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(attachment.contentType())
                && validDocxArchive(content);
        if (!pdf && !docx) return ValidationResult.ignored(IgnoredDocumentReason.UNSUPPORTED_FORMAT);
        return new ValidationResult(pdf ? "application/pdf" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null);
    }

    private boolean validDocxArchive(byte[] content) {
        long expandedBytes = 0;
        boolean hasContentTypes = false;
        boolean hasDocumentXml = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                hasContentTypes |= "[Content_Types].xml".equals(entry.getName());
                hasDocumentXml |= "word/document.xml".equals(entry.getName());
                for (int read; (read = zip.read(buffer)) != -1;) {
                    expandedBytes += read;
                    if (expandedBytes > 50L * 1024 * 1024) return false;
                }
            }
            return hasContentTypes && hasDocumentXml;
        } catch (IOException exception) { return false; }
    }

    private boolean startsWith(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (bytes[i] != expected[i]) return false;
        return true;
    }

    public record ValidationResult(String contentType, IgnoredDocumentReason ignoredReason) {
        public static ValidationResult ignored(IgnoredDocumentReason reason) { return new ValidationResult(null, reason); }
        public boolean accepted() { return ignoredReason == null; }
    }
}
