package com.cvmatcher.cv_matcher_backend.ingestion.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class PdfDocxTextExtractor implements TextExtractor {
    @Override public String extract(byte[] content, String contentType) {
        try {
            String text = "application/pdf".equals(contentType) ? extractPdf(content) : extractDocx(content);
            String normalized = text.replaceAll("\\s+", " ").trim();
            if (normalized.length() < 50) throw new IllegalArgumentException("Text is too short");
            return normalized;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Document text extraction failed", exception);
        }
    }
    private String extractPdf(byte[] content) throws IOException {
        try (var document = Loader.loadPDF(content)) { return new PDFTextStripper().getText(document); }
    }
    private String extractDocx(byte[] content) throws IOException {
        try (var document = new XWPFDocument(new ByteArrayInputStream(content)); var extractor = new XWPFWordExtractor(document)) { return extractor.getText(); }
    }
}
