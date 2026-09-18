package com.cvmatcher.cv_matcher_backend.document;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentInspectorTest {
    @Test void identifiesPdfByContentRatherThanFilenameOrMime() throws Exception {
        assertEquals(DocumentFormat.PDF, DocumentInspector.inspect(validPdf()).format());
        assertEquals("UNSUPPORTED_FORMAT", DocumentInspector.inspect("not a PDF".getBytes(StandardCharsets.US_ASCII)).reason());
    }
    @Test void rejectsEncryptedAndTruncatedPdf() {
        assertEquals("PASSWORD_PROTECTED", DocumentInspector.inspect("%PDF-1.7 /Encrypt %%EOF".getBytes(StandardCharsets.US_ASCII)).reason());
        assertEquals("CORRUPT_DOCUMENT", DocumentInspector.inspect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)).reason());
    }
    @Test void identifiesDocxPackage() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml")); zip.write("x".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write("x".getBytes()); zip.closeEntry();
        }
        assertEquals(DocumentFormat.DOCX, DocumentInspector.inspect(output.toByteArray()).format());
    }
    private static byte[] validPdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
