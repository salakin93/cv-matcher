package com.cvmatcher.cv_matcher_backend.outlook.application;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.AesGcmCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
final class OutlookConnectionStore {
    private final JdbcTemplate jdbc;
    private final OutlookProperties properties;
    private final TransactionTemplate transactions;
    private final OutlookObservability observability;

    OutlookConnectionStore(JdbcTemplate jdbc, OutlookProperties properties, TransactionTemplate transactions, OutlookObservability observability) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.transactions = transactions;
        this.observability = observability;
    }

    OutlookService.Status status() {
        return jdbc.queryForObject("select status,connected_at,last_token_refresh_at,granted_scopes,last_error_code,updated_at from outlook_connection where id=1", (result, row) -> new OutlookService.Status(result.getString(1), instant(result, 2), instant(result, 3), Arrays.asList((String[]) result.getArray(4).getArray()), result.getString(5), instant(result, 6)));
    }

    Connection connection() {
        return jdbc.query("select status,refresh_token_ciphertext,refresh_token_key_version,version from outlook_connection where id=1", result -> result.next() ? new Connection(result.getString(1), result.getBytes(2), result.getInt(3), result.getLong(4)) : null);
    }

    void connect(String refresh, String tenantId, String subject, String[] scopes, UUID actor) {
        transactions.executeWithoutResult(status -> {
            var previousStatus = lock();
            jdbc.update("update outlook_connection set status='CONNECTED',tenant_id=?,account_subject=?,refresh_token_ciphertext=?,refresh_token_key_version=?,granted_scopes=?,connected_at=current_timestamp,last_token_refresh_at=null,last_error_code=null,updated_at=current_timestamp,version=version+1 where id=1", tenantId, subject, cipher().encrypt(refresh.getBytes(StandardCharsets.UTF_8)), properties.tokenEncryptionKeyVersion(), scopes);
            observability.audit(actor, "NOT_CONNECTED".equals(previousStatus) ? "OUTLOOK_CONNECTION_ESTABLISHED" : "OUTLOOK_CONNECTION_REAUTHORIZED");
        });
    }

    boolean applyRefreshRotation(byte[] expectedCiphertext, String rotatedRefreshToken) {
        return transactions.execute(status -> {
            lock();
            var current = jdbc.queryForObject("select refresh_token_ciphertext from outlook_connection where id=1", byte[].class);
            if (!Arrays.equals(expectedCiphertext, current)) return false;
            jdbc.update("update outlook_connection set refresh_token_ciphertext=coalesce(?,refresh_token_ciphertext),refresh_token_key_version=?,last_token_refresh_at=current_timestamp,updated_at=current_timestamp,version=version+1 where id=1", rotatedRefreshToken == null ? null : cipher().encrypt(rotatedRefreshToken.getBytes(StandardCharsets.UTF_8)), properties.tokenEncryptionKeyVersion());
            return true;
        });
    }

    void requireReauthorization(Connection expectedConnection) {
        transactions.executeWithoutResult(status -> {
            lock();
            var current = connection();
            if (current == null || current.version() != expectedConnection.version()
                    || !Arrays.equals(current.ciphertext(), expectedConnection.ciphertext())) return;
            transitionToReauthorizationRequired();
        });
    }

    void requireReauthorization() {
        transactions.executeWithoutResult(status -> {
            lock();
            transitionToReauthorizationRequired();
        });
    }

    void markDecryptionFailed() {
        transactions.executeWithoutResult(status -> {
            lock();
            jdbc.update("update outlook_connection set status='ERROR',last_error_code='TOKEN_DECRYPTION_FAILED',updated_at=current_timestamp,version=version+1 where id=1");
        });
    }

    String decrypt(Connection connection) {
        return new String(cipher().decrypt(connection.ciphertext()), StandardCharsets.UTF_8);
    }

    private String lock() {
        return jdbc.queryForObject("select status from outlook_connection where id=1 for update", String.class);
    }

    private void transitionToReauthorizationRequired() {
        jdbc.update("update outlook_connection set status='REAUTHORIZATION_REQUIRED',refresh_token_ciphertext=null,last_error_code=null,updated_at=current_timestamp,version=version+1 where id=1");
        observability.audit(null, "OUTLOOK_REAUTHORIZATION_REQUIRED");
    }

    private AesGcmCipher cipher() {
        return new AesGcmCipher(properties.tokenEncryptionKey());
    }

    private static Instant instant(java.sql.ResultSet result, int column) throws java.sql.SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record Connection(String status, byte[] ciphertext, int keyVersion, long version) {
    }
}
