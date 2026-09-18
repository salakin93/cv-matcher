package com.cvmatcher.cv_matcher_backend.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
final class DocumentPersistence {
    private final JdbcTemplate jdbc;
    DocumentPersistence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    boolean resolved(UUID jobId, String messageId, String attachmentId) {
        var value = jdbc.queryForObject("select count(*) from matching_job_document j join candidate_document d on d.id=j.candidate_document_id where j.matching_job_id=? and d.graph_message_id_hash=? and d.graph_attachment_id_hash=?", Integer.class, jobId, hash(messageId), hash(attachmentId));
        return value != null && value > 0;
    }
    @Transactional(readOnly = true)
    boolean hasAvailable(UUID jobId) {
        var value = jdbc.queryForObject("select count(*) from matching_job_document where matching_job_id=? and disposition='ACCEPTED'", Integer.class, jobId);
        return value != null && value > 0;
    }

    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    static String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
