package com.cvmatcher.cv_matcher_backend.ingestion.service;

import com.cvmatcher.cv_matcher_backend.ingestion.domain.IgnoredDocumentReason;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphClient.GraphAttachment;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DocumentValidator {
    static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    public ValidationResult validate(GraphAttachment attachment) {
        byte[] content = attachment.content();
        if (attachment.inline() || content == null || content.length == 0) return ValidationResult.ignored(IgnoredDocumentReason.EMPTY_FILE);
        if (content.length > MAX_FILE_SIZE) return ValidationResult.ignored(IgnoredDocumentReason.OVERSIZED);
        String name = attachment.name() == null ? "" : attachment.name().toLowerCase(Locale.ROOT);
        boolean pdf = name.endsWith(".pdf") && startsWith(content, "%PDF-".getBytes());
        boolean docx = name.endsWith(".docx") && startsWith(content, new byte[] {'P', 'K', 3, 4});
        if (!pdf && !docx) return ValidationResult.ignored(IgnoredDocumentReason.UNSUPPORTED_FORMAT);
        return new ValidationResult(pdf ? "application/pdf" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null);
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
