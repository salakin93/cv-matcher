package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookAccessTokenPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookException;
import com.cvmatcher.cv_matcher_backend.outlook.infrastructure.AesGcmCipher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OutlookIntegrationTest {
    private static final KeyPair OIDC_KEY_PAIR = oidcKeyPair();
    private static final ExecutorService OAUTH_EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer OAUTH_DOUBLE = startOAuthDouble();
    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> ID_TOKEN = new AtomicReference<>();
    private static final Map<String, String> ID_TOKENS_BY_CODE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicReference<TokenResponder> TOKEN_RESPONDER = new AtomicReference<>(OutlookIntegrationTest::successResponse);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwt;
    @Autowired
    private OutlookAccessTokenPort accessTokens;

    @DynamicPropertySource
    static void outlookProperties(DynamicPropertyRegistry properties) {
        properties.add("app.outlook.tenant-id", () -> "test-tenant");
        properties.add("app.outlook.client-id", () -> "test-client");
        properties.add("app.outlook.client-secret", () -> "test-secret");
        properties.add("app.outlook.authority", () -> "http://127.0.0.1:" + OAUTH_DOUBLE.getAddress().getPort());
        properties.add("app.outlook.redirect-uri", () -> "http://localhost:6060/api/v1/admin/integrations/outlook/callback");
        properties.add("app.outlook.app-base-url", () -> "http://localhost:5173");
        properties.add("app.outlook.token-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        properties.add("app.outlook.token-encryption-key-version", () -> "1");
        properties.add("app.outlook.connect-timeout", () -> "1s");
        properties.add("app.outlook.read-timeout", () -> "1s");
        properties.add("app.outlook.max-retries", () -> "3");
    }

    @AfterAll
    static void stopOAuthDouble() {
        OAUTH_DOUBLE.stop(0);
        OAUTH_EXECUTOR.shutdownNow();
    }

    @BeforeEach
    void reset() {
        TOKEN_REQUESTS.set(0);
        ID_TOKEN.set(null);
        ID_TOKENS_BY_CODE.clear();
        TOKEN_RESPONDER.set(OutlookIntegrationTest::successResponse);
        jdbc.update("delete from audit_event where target_type='OUTLOOK_CONNECTION'");
        jdbc.update("delete from outlook_authorization_attempt");
        jdbc.update("update outlook_connection set status='NOT_CONNECTED',tenant_id=null,account_subject=null,granted_scopes='{}',refresh_token_ciphertext=null,last_error_code=null,version=0,updated_at=current_timestamp where id=1");
    }

    @Test
    void callbackSuccessReplayExpiryAndUnknownJsonAreSafe() throws Exception {
        var actor = user("outlook-admin@example.test", "ADMIN");
        var bearer = "Bearer " + jwt.issue(actor, "ADMIN", session(actor));
        mockMvc.perform(post("/api/v1/admin/integrations/outlook/authorization").header("Authorization", bearer)
                        .contentType("application/json").content("{\"unexpected\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        var authorization = startAuthorizationAttempt(bearer);
        assertEquals(1L, jdbc.queryForObject("select count(*) from outlook_authorization_attempt", Long.class));
        assertFalse(jdbc.queryForObject("select encode(code_verifier_ciphertext,'escape') from outlook_authorization_attempt", String.class).contains("code_verifier"));
        ID_TOKEN.set(signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false));
        mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", authorization.state()).param("code", "test-code"))
                .andExpect(status().isFound());
        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from outlook_authorization_attempt", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_ESTABLISHED'", Long.class));
        mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", authorization.state()).param("code", "test-code"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));

        var expired = startAuthorization(bearer);
        jdbc.update("update outlook_authorization_attempt set expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", expired).param("code", "test-code"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OAUTH_STATE_INVALID"));
    }

    @Test
    void startAuthorizationDeletesExpiredAttemptsAndRetainsAnotherActorsValidAttempt() throws Exception {
        var expiredActor = user("outlook-expired-start@example.test", "ADMIN");
        var validActor = user("outlook-valid-start@example.test", "ADMIN");
        startAuthorizationAttempt("Bearer " + jwt.issue(validActor, "ADMIN", session(validActor)));
        insertExpiredAttempt(expiredActor);

        startAuthorization(adminBearer("outlook-expired-start-actor@example.test"));

        assertEquals(0L, jdbc.queryForObject("select count(*) from outlook_authorization_attempt where expires_at<=current_timestamp", Long.class));
        assertValidUnconsumedAttempt(validActor);
    }

    @Test
    void concurrentStartsByTheSameActorLeaveOnlyTheLatestActiveAttempt() throws Exception {
        var bearer = adminBearer("outlook-concurrent-start@example.test");
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> concurrentStart(bearer, ready, release));
            Future<String> second = executor.submit(() -> concurrentStart(bearer, ready, release));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1L, jdbc.queryForObject("select count(*) from outlook_authorization_attempt where initiated_by_user_id=(select id from user_account where email_normalized=?) and consumed_at is null and expires_at>current_timestamp", Long.class, "outlook-concurrent-start@example.test"));
    }

    @Test
    void consumeAuthorizationDeletesExpiredAttemptsAndRetainsAnotherActorsValidAttempt() throws Exception {
        var authorization = startAuthorizationAttempt(adminBearer("outlook-expired-consume@example.test"));
        var validActor = user("outlook-valid-consume@example.test", "ADMIN");
        startAuthorizationAttempt("Bearer " + jwt.issue(validActor, "ADMIN", session(validActor)));
        insertExpiredAttempt(user("outlook-expired-consume-actor@example.test", "ADMIN"));
        ID_TOKEN.set(signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false));

        mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", authorization.state()).param("code", "test-code"))
                .andExpect(status().isFound());

        assertEquals(0L, jdbc.queryForObject("select count(*) from outlook_authorization_attempt where expires_at<=current_timestamp", Long.class));
        assertValidUnconsumedAttempt(validActor);
    }

    @Test
    void oidcValidationAcceptsSignedTokenWithExpectedIssuerAudienceTenantExpiryAndNonce() throws Exception {
        var actor = user("outlook-oidc-valid@example.test", "ADMIN");
        var authorization = startAuthorizationAttempt("Bearer " + jwt.issue(actor, "ADMIN", session(actor)));
        ID_TOKEN.set(signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false));

        mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", authorization.state()).param("code", "test-code"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:5173/admin/integrations/outlook/callback?result=connected"));
        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("test-tenant", jdbc.queryForObject("select tenant_id from outlook_connection where id=1", String.class));
        assertEquals("technical-subject", jdbc.queryForObject("select account_subject from outlook_connection where id=1", String.class));
    }

    @Test
    void oidcValidationRejectsInvalidIssuerAudienceTenantExpirySignatureAndNonce() throws Exception {
        assertOidcTokenRejected("wrong issuer", authorization -> signedIdToken(issuer() + "/other", "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false));
        assertOidcTokenRejected("wrong audience", authorization -> signedIdToken(issuer(), "other-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false));
        assertOidcTokenRejected("wrong tenant", authorization -> signedIdToken(issuer(), "test-client", "other-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false));
        assertOidcTokenRejected("expired token", authorization -> signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().minusSeconds(300), authorization.nonce(), false));
        assertOidcTokenRejected("invalid signature", authorization -> signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), true));
        assertOidcTokenRejected("wrong nonce", authorization -> signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), "other-nonce", false));
    }

    @Test
    void oidcValidationRejectsMissingOrBlankSubjectBeforeConnectionPersistence() throws Exception {
        assertOidcTokenRejected("missing subject", authorization -> signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false, null));
        assertOidcTokenRejected("blank subject", authorization -> signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false, "  "));
    }

    @Test
    void migrationEnforcesCanonicalScopesAndConnectedFields() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update outlook_connection set granted_scopes=? where id=1", (Object) new String[]{"profile", "openid", "offline_access"}));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update outlook_connection set granted_scopes=? where id=1", (Object) new String[]{"offline_access", "openid", "openid", "profile"}));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update outlook_connection set granted_scopes=? where id=1", (Object) new String[]{" offline_access", "openid", "profile"}));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update outlook_connection set granted_scopes=? where id=1", (Object) new String[]{"offline_access", "", "openid", "profile"}));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update outlook_connection set granted_scopes=? where id=1", (Object) new String[]{"offline_access", "   ", "openid", "profile"}));

        assertConnectedConstraintRejects(null, "tenant", "subject", new String[]{"offline_access", "openid", "profile"});
        assertConnectedConstraintRejects(new byte[]{1}, " ", "subject", new String[]{"offline_access", "openid", "profile"});
        assertConnectedConstraintRejects(new byte[]{1}, "tenant", " ", new String[]{"offline_access", "openid", "profile"});
        assertConnectedConstraintRejects(new byte[]{1}, "tenant", "subject", new String[]{});
    }

    @Test
    void administrativeStatusRequiresAdminAndOnlyExposesSafeFields() throws Exception {
        var recruiter = user("outlook-recruiter@example.test", "RECRUITER");
        mockMvc.perform(get("/api/v1/admin/integrations/outlook"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/admin/integrations/outlook").header("Authorization", "Bearer " + jwt.issue(recruiter, "RECRUITER", session(recruiter))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void openApiDocumentsTheOutlookAdministrativeContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/admin/integrations/outlook")));
    }

    @Test
    void refreshIsCachedRotatedAndInvalidGrantRequiresReauthorization() throws Exception {
        var actor = user("outlook-refresh@example.test", "ADMIN");
        connect("Bearer " + jwt.issue(actor, "ADMIN", session(actor)));
        assertEquals("access-token", accessTokens.accessToken().value());
        assertEquals("access-token", accessTokens.accessToken().value());
        assertEquals(2, TOKEN_REQUESTS.get()); // Authorization exchange plus one cached refresh.
        assertEquals(2L, jdbc.queryForObject("select version from outlook_connection where id=1", Long.class));
        assertEquals("rotated-refresh-token", decryptCurrentRefreshToken());
    }

    @Test
    void invalidGrantRequiresReauthorizationWithoutRetryOrFurtherTokenCalls() throws Exception {
        var actor = user("outlook-invalid-grant@example.test", "ADMIN");
        connect("Bearer " + jwt.issue(actor, "ADMIN", session(actor)));
        TOKEN_RESPONDER.set(form -> form.contains("grant_type=refresh_token")
                ? new TokenResponse(400, "{\"error\":\"invalid_grant\"}", null) : successResponse(form));

        var exception = assertThrows(OutlookException.class, () -> accessTokens.accessToken());

        assertEquals("OUTLOOK_REAUTHORIZATION_REQUIRED", exception.code());
        assertEquals(2, TOKEN_REQUESTS.get());
        assertEquals("REAUTHORIZATION_REQUIRED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertNull(jdbc.queryForObject("select refresh_token_ciphertext from outlook_connection where id=1", byte[].class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_REAUTHORIZATION_REQUIRED'", Long.class));
        assertThrows(OutlookException.class, () -> accessTokens.accessToken());
        assertEquals(2, TOKEN_REQUESTS.get());
    }

    @Test
    void retriesThrottlingAndTransientFailuresUsingRetryAfterBeforeRotatingTheToken() throws Exception {
        var actor = user("outlook-retry@example.test", "ADMIN");
        connect("Bearer " + jwt.issue(actor, "ADMIN", session(actor)));
        var refreshAttempts = new AtomicInteger();
        TOKEN_RESPONDER.set(form -> {
            if (!form.contains("grant_type=refresh_token")) return successResponse(form);
            return switch (refreshAttempts.getAndIncrement()) {
                case 0 -> new TokenResponse(429, "{\"error\":\"throttled\"}", "0");
                case 1 -> new TokenResponse(503, "{\"error\":\"temporarily_unavailable\"}", null);
                default -> successResponse(form);
            };
        });

        assertEquals("access-token", accessTokens.accessToken().value());

        assertEquals(4, TOKEN_REQUESTS.get());
        assertEquals(3, refreshAttempts.get());
        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("rotated-refresh-token", decryptCurrentRefreshToken());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_ESTABLISHED'", Long.class));
    }

    @Test
    void exhaustedTransientFailuresKeepTheCurrentCredentialAndReportTemporaryUnavailability() throws Exception {
        connect(adminBearer("outlook-transient-exhausted@example.test"));
        TOKEN_RESPONDER.set(form -> form.contains("grant_type=refresh_token")
                ? new TokenResponse(503, "{\"error\":\"temporarily_unavailable\"}", null) : successResponse(form));

        var exception = assertThrows(OutlookException.class, () -> accessTokens.accessToken());

        assertEquals("OUTLOOK_TEMPORARILY_UNAVAILABLE", exception.code());
        assertEquals(4, TOKEN_REQUESTS.get());
        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("rotated-refresh-token", decryptCurrentRefreshToken());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_ESTABLISHED'", Long.class));
    }

    @Test
    void concurrentCallbacksLeaveOneConnectedDecryptableCredentialAndCoherentAudits() throws Exception {
        var first = startAuthorizationAttempt(adminBearer("outlook-callback-one@example.test"));
        var second = startAuthorizationAttempt(adminBearer("outlook-callback-two@example.test"));
        ID_TOKENS_BY_CODE.put("callback-one", signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), first.nonce(), false));
        ID_TOKENS_BY_CODE.put("callback-two", signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), second.nonce(), false));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var firstCallback = executor.submit(() -> callback(first.state(), "callback-one"));
            var secondCallback = executor.submit(() -> callback(second.state(), "callback-two"));
            assertEquals(302, firstCallback.get(10, TimeUnit.SECONDS));
            assertEquals(302, secondCallback.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1L, jdbc.queryForObject("select count(*) from outlook_connection", Long.class));
        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("rotated-refresh-token", decryptCurrentRefreshToken());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_ESTABLISHED'", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_REAUTHORIZED'", Long.class));
    }

    @Test
    void concurrentRefreshUsesOneProviderCallAndLeavesTheRotatedCiphertextDecryptable() throws Exception {
        connect(adminBearer("outlook-concurrent-refresh@example.test"));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> accessTokens.accessToken().value());
            Future<String> second = executor.submit(() -> accessTokens.accessToken().value());
            assertEquals("access-token", first.get(10, TimeUnit.SECONDS));
            assertEquals("access-token", second.get(10, TimeUnit.SECONDS));
        }

        assertEquals(2, TOKEN_REQUESTS.get());
        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("rotated-refresh-token", decryptCurrentRefreshToken());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_ESTABLISHED'", Long.class));
    }

    @Test
    void callbackWinsOverAnInFlightRefreshAndTheStaleRotationIsNotPersisted() throws Exception {
        connect(adminBearer("outlook-refresh-callback@example.test"));
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        var refreshAttempts = new AtomicInteger();
        TOKEN_RESPONDER.set(form -> {
            if (!form.contains("grant_type=refresh_token")) {
                if (form.contains("code=racing-callback")) return new TokenResponse(200, tokenBody("callback-refresh-token", ID_TOKENS_BY_CODE.get("racing-callback")), null);
                return successResponse(form);
            }
            refreshStarted.countDown();
            try {
                assertTrue(releaseRefresh.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new TokenResponse(200, tokenBody(refreshAttempts.getAndIncrement() == 0 ? "stale-rotated-refresh-token" : "fresh-rotated-refresh-token", null), null);
        });
        var callback = startAuthorizationAttempt(adminBearer("outlook-racing-callback@example.test"));
        ID_TOKENS_BY_CODE.put("racing-callback", signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), callback.nonce(), false));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var refreshing = executor.submit(() -> accessTokens.accessToken().value());
            assertTrue(refreshStarted.await(10, TimeUnit.SECONDS));
            assertEquals(302, executor.submit(() -> callback(callback.state(), "racing-callback")).get(10, TimeUnit.SECONDS));
            releaseRefresh.countDown();
            assertEquals("access-token", refreshing.get(10, TimeUnit.SECONDS));
        }

        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("fresh-rotated-refresh-token", decryptCurrentRefreshToken());
        assertEquals(4, TOKEN_REQUESTS.get());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_ESTABLISHED'", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_CONNECTION_REAUTHORIZED'", Long.class));
    }

    @Test
    void callbackWinsOverAnInFlightInvalidGrantAndKeepsItsCredential() throws Exception {
        connect(adminBearer("outlook-invalid-grant-callback@example.test"));
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        TOKEN_RESPONDER.set(form -> {
            if (!form.contains("grant_type=refresh_token")) {
                if (form.contains("code=racing-invalid-grant-callback"))
                    return new TokenResponse(200, tokenBody("callback-refresh-token", ID_TOKENS_BY_CODE.get("racing-invalid-grant-callback")), null);
                return successResponse(form);
            }
            refreshStarted.countDown();
            try {
                assertTrue(releaseRefresh.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new TokenResponse(400, "{\"error\":\"invalid_grant\"}", null);
        });
        var callback = startAuthorizationAttempt(adminBearer("outlook-racing-invalid-grant-callback@example.test"));
        ID_TOKENS_BY_CODE.put("racing-invalid-grant-callback", signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), callback.nonce(), false));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var refreshing = executor.submit(() -> accessTokens.accessToken().value());
            assertTrue(refreshStarted.await(10, TimeUnit.SECONDS));
            assertEquals(302, executor.submit(() -> callback(callback.state(), "racing-invalid-grant-callback")).get(10, TimeUnit.SECONDS));
            releaseRefresh.countDown();
            var failure = assertThrows(ExecutionException.class, () -> refreshing.get(10, TimeUnit.SECONDS));
            assertInstanceOf(OutlookException.class, failure.getCause());
            assertEquals("OUTLOOK_REAUTHORIZATION_REQUIRED", ((OutlookException) failure.getCause()).code());
        }

        assertEquals("CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("callback-refresh-token", decryptCurrentRefreshToken());
        assertEquals(0L, jdbc.queryForObject("select count(*) from audit_event where action='OUTLOOK_REAUTHORIZATION_REQUIRED'", Long.class));
    }

    @Test
    void invalidAesTagMarksErrorAndPreservesCiphertextWithoutCallingTheDouble() throws Exception {
        var actor = user("outlook-cipher@example.test", "ADMIN");
        connect("Bearer " + jwt.issue(actor, "ADMIN", session(actor)));
        var original = jdbc.queryForObject("select refresh_token_ciphertext from outlook_connection where id=1", byte[].class);
        original[original.length - 1] ^= 1;
        jdbc.update("update outlook_connection set refresh_token_ciphertext=? where id=1", original);
        var requests = TOKEN_REQUESTS.get();
        var exception = assertThrows(OutlookException.class, () -> accessTokens.accessToken());
        assertEquals("TOKEN_DECRYPTION_FAILED", exception.code());
        assertEquals(requests, TOKEN_REQUESTS.get());
        assertEquals("ERROR", jdbc.queryForObject("select status from outlook_connection where id=1", String.class));
        assertEquals("TOKEN_DECRYPTION_FAILED", jdbc.queryForObject("select last_error_code from outlook_connection where id=1", String.class));
        assertFalse(java.util.Arrays.equals(new byte[0], jdbc.queryForObject("select refresh_token_ciphertext from outlook_connection where id=1", byte[].class)));
    }

    private void connect(String bearer) throws Exception {
        var authorization = startAuthorizationAttempt(bearer);
        ID_TOKEN.set(signedIdToken(issuer(), "test-client", "test-tenant", Instant.now().plusSeconds(300), authorization.nonce(), false));
        mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", authorization.state()).param("code", "test-code")).andExpect(status().isFound());
    }

    private String adminBearer(String email) throws Exception {
        var actor = user(email, "ADMIN");
        return "Bearer " + jwt.issue(actor, "ADMIN", session(actor));
    }

    private int callback(String state, String code) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", state).param("code", code))
                .andReturn().getResponse().getStatus();
    }

    private String decryptCurrentRefreshToken() {
        var ciphertext = jdbc.queryForObject("select refresh_token_ciphertext from outlook_connection where id=1", byte[].class);
        return new String(new AesGcmCipher(Base64.getEncoder().encodeToString(new byte[32])).decrypt(ciphertext), StandardCharsets.UTF_8);
    }

    private String startAuthorization(String bearer) throws Exception {
        return startAuthorizationAttempt(bearer).state();
    }

    private AuthorizationAttempt startAuthorizationAttempt(String bearer) throws Exception {
        var body = mockMvc.perform(post("/api/v1/admin/integrations/outlook/authorization").header("Authorization", bearer).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new AuthorizationAttempt(body.replaceAll(".*[?&]state=([^&\\\"]+).*", "$1"), body.replaceAll(".*[?&]nonce=([^&\\\"]+).*", "$1"));
    }

    private String concurrentStart(String bearer, CountDownLatch ready, CountDownLatch release) throws Exception {
        ready.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS));
        return startAuthorization(bearer);
    }

    private void assertOidcTokenRejected(String ignored, TokenFactory tokenFactory) throws Exception {
        var actor = user("outlook-oidc-" + UUID.randomUUID() + "@example.test", "ADMIN");
        var authorization = startAuthorizationAttempt("Bearer " + jwt.issue(actor, "ADMIN", session(actor)));
        ID_TOKEN.set(tokenFactory.create(authorization));
        mockMvc.perform(get("/api/v1/admin/integrations/outlook/callback").param("state", authorization.state()).param("code", "test-code"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:5173/admin/integrations/outlook/callback?result=error"));
        assertEquals("NOT_CONNECTED", jdbc.queryForObject("select status from outlook_connection where id=1", String.class), ignored);
    }

    private void assertConnectedConstraintRejects(byte[] ciphertext, String tenantId, String subject, String[] scopes) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update outlook_connection set status='CONNECTED',refresh_token_ciphertext=?,tenant_id=?,account_subject=?,granted_scopes=? where id=1", ciphertext, tenantId, subject, scopes));
    }

    private void insertExpiredAttempt(UUID actor) {
        jdbc.update("insert into outlook_authorization_attempt(id,state_hash,code_verifier_ciphertext,nonce_hash,initiated_by_user_id,expires_at,created_at) values(?,?,?,?,?,?,?)",
                UUID.randomUUID(), UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII), new byte[]{1}, new byte[]{2}, actor,
                Timestamp.from(Instant.now().minusSeconds(1)), Timestamp.from(Instant.now()));
    }

    private void assertValidUnconsumedAttempt(UUID actor) {
        assertEquals(1L, jdbc.queryForObject("select count(*) from outlook_authorization_attempt where initiated_by_user_id=? and consumed_at is null and expires_at>current_timestamp", Long.class, actor));
    }

    private UUID user(String email, String role) {
        var id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,?, 'ACTIVE',?,false,?,?)", id, email, email, email, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("ClaveSegura1"), role, now, now, now);
        return id;
    }

    private UUID session(UUID user) throws Exception {
        var id = UUID.randomUUID();
        jdbc.update("insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)", id, user, Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(("session-" + id).getBytes(StandardCharsets.UTF_8))), Timestamp.from(Instant.now().plusSeconds(300)), Timestamp.from(Instant.now()));
        return id;
    }

    private static HttpServer startOAuthDouble() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2.0/.well-known/openid-configuration", exchange -> respond(exchange, 200,
                    "{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + authority() + "/keys\"}", null));
            server.createContext("/keys", exchange -> respond(exchange, 200, jwks(), null));
            server.createContext("/oauth2/v2.0/token", exchange -> {
                TOKEN_REQUESTS.incrementAndGet();
                var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var response = TOKEN_RESPONDER.get().respond(form);
                respond(exchange, response.status(), response.body(), response.retryAfter());
            });
            server.setExecutor(OAUTH_EXECUTOR);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String authority() {
        return "http://127.0.0.1:" + OAUTH_DOUBLE.getAddress().getPort();
    }

    private static String issuer() {
        return authority() + "/v2.0";
    }

    private static String jwks() {
        var key = (RSAPublicKey) OIDC_KEY_PAIR.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-key\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"" + base64Url(unsigned(key.getModulus().toByteArray())) + "\",\"e\":\"" + base64Url(unsigned(key.getPublicExponent().toByteArray())) + "\"}]}";
    }

    private static String signedIdToken(String issuer, String audience, String tenant, Instant expiresAt, String nonce, boolean invalidSignature) {
        return signedIdToken(issuer, audience, tenant, expiresAt, nonce, invalidSignature, "technical-subject");
    }

    private static String signedIdToken(String issuer, String audience, String tenant, Instant expiresAt, String nonce, boolean invalidSignature, String subject) {
        var header = base64Url("{\"alg\":\"RS256\",\"kid\":\"test-key\"}".getBytes(StandardCharsets.US_ASCII));
        var subjectClaim = subject == null ? "" : "\"sub\":\"" + subject + "\",";
        var claims = base64Url(("{\"iss\":\"" + issuer + "\"," + subjectClaim + "\"aud\":\"" + audience + "\",\"tid\":\"" + tenant + "\",\"nonce\":\"" + nonce + "\",\"exp\":" + expiresAt.getEpochSecond() + "}").getBytes(StandardCharsets.US_ASCII));
        var signingInput = header + "." + claims;
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(invalidSignature ? oidcKeyPair().getPrivate() : OIDC_KEY_PAIR.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static KeyPair oidcKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static TokenResponse successResponse(String form) {
        var idToken = ID_TOKENS_BY_CODE.entrySet().stream().filter(entry -> form.contains("code=" + entry.getKey())).map(Map.Entry::getValue).findFirst().orElse(ID_TOKEN.get());
        return new TokenResponse(200, tokenBody("rotated-refresh-token", idToken), null);
    }

    private static String tokenBody(String refreshToken, String idToken) {
        return "{\"access_token\":\"access-token\",\"expires_in\":3600,\"refresh_token\":\"" + refreshToken + "\",\"id_token\":\"" + idToken + "\",\"scope\":\"openid profile offline_access\"}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String response, String retryAfter) throws IOException {
        var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] unsigned(byte[] value) {
        return value[0] == 0 ? java.util.Arrays.copyOfRange(value, 1, value.length) : value;
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record AuthorizationAttempt(String state, String nonce) {
    }

    private record TokenResponse(int status, String body, String retryAfter) {
    }

    @FunctionalInterface
    private interface TokenResponder {
        TokenResponse respond(String form);
    }

    @FunctionalInterface
    private interface TokenFactory {
        String create(AuthorizationAttempt authorization);
    }
}
