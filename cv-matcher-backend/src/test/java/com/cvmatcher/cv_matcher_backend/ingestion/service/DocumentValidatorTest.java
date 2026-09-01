package com.cvmatcher.cv_matcher_backend.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.cvmatcher.cv_matcher_backend.ingestion.domain.IgnoredDocumentReason;
import com.cvmatcher.cv_matcher_backend.ingestion.graph.MicrosoftGraphClient.GraphAttachment;
import org.junit.jupiter.api.Test;

class DocumentValidatorTest {
    private final DocumentValidator validator = new DocumentValidator();

    @Test void acceptsPdfOnlyWithMagicBytes() {
        var result = validator.validate(new GraphAttachment("a", "cv.pdf", "application/pdf", false, "%PDF-1.7".getBytes()));
        assertThat(result.accepted()).isTrue();
        assertThat(result.contentType()).isEqualTo("application/pdf");
    }

    @Test void rejectsExtensionWithoutValidSignature() {
        var result = validator.validate(new GraphAttachment("a", "cv.pdf", "application/pdf", false, "not a pdf".getBytes()));
        assertThat(result.ignoredReason()).isEqualTo(IgnoredDocumentReason.UNSUPPORTED_FORMAT);
    }

    @Test void rejectsPdfWithInconsistentMimeType() {
        var result = validator.validate(new GraphAttachment("a", "cv.pdf", "image/png", false, "%PDF-1.7".getBytes()));
        assertThat(result.ignoredReason()).isEqualTo(IgnoredDocumentReason.UNSUPPORTED_FORMAT);
    }
}
