package com.cvmatcher.cv_matcher_backend.document;

import org.apache.pdfbox.Loader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

final class DocumentInspector {
    private DocumentInspector() {}
    static Result inspect(byte[] bytes) {
        if (bytes.length == 0) return new Result(null, "EMPTY_DOCUMENT");
        if (startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            try (var document = Loader.loadPDF(bytes)) {
                return document.isEncrypted() ? new Result(null, "PASSWORD_PROTECTED") : new Result(DocumentFormat.PDF, null);
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException exception) {
                return new Result(null, "PASSWORD_PROTECTED");
            } catch (Exception exception) {
                return new Result(null, "CORRUPT_DOCUMENT");
            }
        }
        if (!startsWith(bytes, new byte[]{'P', 'K', 3, 4})) return new Result(null, "UNSUPPORTED_FORMAT");
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            boolean contentTypes = false, document = false;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("[Content_Types].xml".equals(entry.getName())) contentTypes = true;
                if ("word/document.xml".equals(entry.getName())) document = true;
            }
            return contentTypes && document ? new Result(DocumentFormat.DOCX, null) : new Result(null, "CORRUPT_DOCUMENT");
        } catch (Exception exception) { return new Result(null, "CORRUPT_DOCUMENT"); }
    }
    private static boolean startsWith(byte[] value, byte[] prefix) { if (value.length < prefix.length) return false; for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false; return true; }
    record Result(DocumentFormat format, String reason) {}
}
