package com.cvmatcher.cv_matcher_backend.outlook.application;

import com.cvmatcher.cv_matcher_backend.TestcontainersConfiguration;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.AesGcmCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutlookConnectionStoreConcurrencyIntegrationTest {
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutlookConnectionStore connections;

    @DynamicPropertySource
    static void outlookProperties(DynamicPropertyRegistry properties) {
        properties.add("app.outlook.token-encryption-key", () -> ENCRYPTION_KEY);
        properties.add("app.outlook.token-encryption-key-version", () -> "1");
    }

    @BeforeEach
    void resetConnection() {
        jdbc.update("update outlook_connection set status='NOT_CONNECTED',tenant_id=null,account_subject=null,granted_scopes='{}',refresh_token_ciphertext=null,last_error_code=null,version=0,updated_at=current_timestamp where id=1");
    }

    @Test
    void callbackCredentialWinsOverStaleKeyVersionDecryptionFailure() {
        var observed = staleConnection(2, false);

        connections.connect("callback-refresh-token", "tenant", "subject", new String[]{"offline_access", "openid", "profile"}, null);
        var callbackCiphertext = currentCiphertext();
        assertFalse(connections.markDecryptionFailed(observed));

        assertConnectedWith(callbackCiphertext);
    }

    @Test
    void callbackCredentialWinsOverStaleTamperedCiphertextDecryptionFailure() {
        var observed = staleConnection(1, true);

        connections.connect("callback-refresh-token", "tenant", "subject", new String[]{"offline_access", "openid", "profile"}, null);
        var callbackCiphertext = currentCiphertext();
        assertFalse(connections.markDecryptionFailed(observed));

        assertConnectedWith(callbackCiphertext);
    }

    private OutlookConnectionStore.Connection staleConnection(int keyVersion, boolean tamperCiphertext) {
        var ciphertext = new AesGcmCipher(ENCRYPTION_KEY).encrypt("stale-refresh-token".getBytes(StandardCharsets.UTF_8));
        if (tamperCiphertext) ciphertext[ciphertext.length - 1] ^= 1;
        jdbc.update("update outlook_connection set status='CONNECTED',tenant_id='tenant',account_subject='subject',granted_scopes=?,refresh_token_ciphertext=?,refresh_token_key_version=?,version=1,updated_at=current_timestamp where id=1", new String[]{"offline_access", "openid", "profile"}, ciphertext, keyVersion);
        return connections.connection();
    }

    private byte[] currentCiphertext() {
        return jdbc.queryForObject("select refresh_token_ciphertext from outlook_connection where id=1", byte[].class);
    }

    private void assertConnectedWith(byte[] expectedCiphertext) {
        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertNull(jdbc.queryForObject("select last_error_code from outlook_connection where id=1", String.class));
        assertArrayEquals(expectedCiphertext, currentCiphertext());
    }
}
