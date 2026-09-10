package com.cvmatcher.cv_matcher_backend.outlook.application;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.AesGcmCipher;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.OutlookOAuthClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class OutlookAuthorizationAttempts {
    private final JdbcTemplate jdbc;
    private final OutlookProperties properties;
    private final OutlookOAuthClient oauthClient;
    private final TransactionTemplate transactions;
    private final OutlookObservability observability;
    private final SecureRandom random = new SecureRandom();

    OutlookAuthorizationAttempts(JdbcTemplate jdbc, OutlookProperties properties, OutlookOAuthClient oauthClient, TransactionTemplate transactions, OutlookObservability observability) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.oauthClient = oauthClient;
        this.transactions = transactions;
        this.observability = observability;
    }

    @Transactional
    public OutlookService.Start start(UUID actor) {
        byte[] state = new byte[32], verifierBytes = new byte[48], nonceBytes = new byte[32];
        random.nextBytes(state);
        random.nextBytes(verifierBytes);
        random.nextBytes(nonceBytes);
        var now = Instant.now();
        var stateValue = Base64.getUrlEncoder().withoutPadding().encodeToString(state);
        var verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        var nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        jdbc.update("update outlook_authorization_attempt set consumed_at=? where initiated_by_user_id=? and consumed_at is null", java.sql.Timestamp.from(now), actor);
        jdbc.update("insert into outlook_authorization_attempt(id,state_hash,code_verifier_ciphertext,nonce_hash,initiated_by_user_id,expires_at,created_at) values(?,?,?,?,?,?,?)", UUID.randomUUID(), hash(state), cipher().encrypt(verifier.getBytes(StandardCharsets.US_ASCII)), hash(nonce.getBytes(StandardCharsets.US_ASCII)), actor, java.sql.Timestamp.from(now.plusSeconds(600)), java.sql.Timestamp.from(now));
        observability.audit(actor, "OUTLOOK_AUTHORIZATION_STARTED");
        observability.authorization("started");
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash(verifier.getBytes(StandardCharsets.US_ASCII)));
        var url = oauthClient.authorizationUrl(stateValue, nonce, challenge);
        return new OutlookService.Start(url, now.plusSeconds(600), "PENDING_AUTHORIZATION");
    }

    Attempt consume(String state) {
        return transactions.execute(status -> {
            byte[] decoded;
            try {
                if (state == null || state.isBlank()) throw new IllegalArgumentException();
                decoded = Base64.getUrlDecoder().decode(state);
            } catch (IllegalArgumentException exception) {
                observability.authorization("state_invalid");
                throw new OutlookException(HttpStatus.BAD_REQUEST, "OAUTH_STATE_INVALID");
            }
            var row = jdbc.query("select code_verifier_ciphertext,nonce_hash,initiated_by_user_id from outlook_authorization_attempt where state_hash=? and consumed_at is null and expires_at>current_timestamp for update", result -> result.next() ? new Attempt(result.getBytes(1), result.getBytes(2), UUID.fromString(result.getString(3))) : null, hash(decoded));
            if (row == null) {
                observability.authorization("state_invalid");
                throw new OutlookException(HttpStatus.BAD_REQUEST, "OAUTH_STATE_INVALID");
            }
            jdbc.update("delete from outlook_authorization_attempt where state_hash=?", hash(decoded));
            return row;
        });
    }

    String decryptVerifier(Attempt attempt) {
        return new String(cipher().decrypt(attempt.verifier()), StandardCharsets.US_ASCII);
    }

    private AesGcmCipher cipher() {
        return new AesGcmCipher(properties.tokenEncryptionKey());
    }

    private static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Attempt(byte[] verifier, byte[] nonceHash, UUID actor) {
    }
}
