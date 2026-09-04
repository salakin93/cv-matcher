package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import com.cvmatcher.cv_matcher_backend.identity.application.IdentityService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwt;

    @Autowired
    private IdentityService identityService;

    @Test
    void registersAnAccountWithPostgresTimestamps() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Recruiter Test",
                                  "email": "recruiter-test@example.test",
                                  "password": "ClaveSegura1"
                                }
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectsMalformedRequestsWithTheDocumentedValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "",
                                  "email": "not-an-email",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void recordsTheRequestCorrelationIdInTheAuditEvent() throws Exception {
        var email = "correlated-registration@example.test";
        var response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Recruiter Test",
                                  "email": "%s",
                                  "password": "ClaveSegura1"
                                }
                                """.formatted(email)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse();

        var correlationId = response.getHeader("X-Correlation-Id");
        var persistedCorrelationId = jdbc.queryForObject(
                "select event.correlation_id from audit_event event join user_account account on account.id=event.target_id where event.action='ACCOUNT_REGISTERED' and account.email_normalized=?",
                UUID.class,
                email
        );
        assertEquals(UUID.fromString(correlationId), persistedCorrelationId);
    }

    @Test
    void documentsPublicAndBearerProtectedIdentityOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.schemas.ApiError").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses.401").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.parameters[0].name").value("cv_refresh"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.parameters[0].in").value("cookie"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.parameters[1].name").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/email-change/confirm'].post.responses.409").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses.422").exists())
                .andExpect(jsonPath("$.components.schemas.TokenResponse.properties.expiresIn.example").value(900))
                .andExpect(jsonPath("$.components.schemas.TokenResponse.properties.user").exists());
    }

    @Test
    void rejectsAPasswordThatDoesNotMeetThePolicyWithoutExposingATrace() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Recruiter Test",
                                  "email": "invalid-password@example.test",
                                  "password": "123654.2026"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"))
                .andExpect(jsonPath("$.message").value("La contraseña debe tener al menos 8 caracteres, una mayúscula, una minúscula y un número."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void allowsEmailChangeConfirmationWithoutAnAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-change/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "invalid-token"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnsANeutralUnauthorizedResponseForInvalidLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "unknown@example.test",
                                  "password": "ClaveSegura1"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Credenciales inválidas."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void returnsExpirationAndSafeIdentityWhenCreatingASession() throws Exception {
        var email = "login-contract@example.test";
        insertActiveUser(email, false);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"ClaveSegura1\"}".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("RECRUITER"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void serializesAuthenticationAndCsrfFailuresWithCodeAndCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void limitsVerificationResendsToThreePerHour() throws Exception {
        var email = "resend-limit@example.test";
        register(email);

        for (var attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(post("/api/v1/auth/email-verification/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "%s"
                                    }
                                    """.formatted(email)))
                    .andExpect(status().isAccepted());
        }

        var attempts = jdbc.queryForObject(
                "select count(*) from verification_resend_attempt attempt join user_account account on account.id=attempt.user_id where account.email_normalized=?",
                Long.class,
                email
        );
        org.junit.jupiter.api.Assertions.assertEquals(3L, attempts);
    }

    @Test
    void doesNotIssuePasswordResetTokensForAnUnverifiedAccount() throws Exception {
        var email = "pending-reset@example.test";
        register(email);

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s"
                                }
                                """.formatted(email)))
                .andExpect(status().isAccepted());

        var tokens = jdbc.queryForObject(
                "select count(*) from account_action_token token join user_account account on account.id=token.user_id where token.purpose='PASSWORD_RESET' and account.email_normalized=?",
                Long.class,
                email
        );
        org.junit.jupiter.api.Assertions.assertEquals(0L, tokens);
    }

    @Test
    void requiresPasswordChangeBeforeOtherProtectedOperations() throws Exception {
        var userId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,'ADMIN','ACTIVE',?,true,?,?)",
                userId,
                "Initial Administrator",
                "force-change@example.test",
                "force-change@example.test",
                "unused-hash",
                now,
                now,
                now
        );

        mockMvc.perform(post("/api/v1/auth/email-change/request")
                        .header("Authorization", "Bearer " + jwt.issue(userId, "ADMIN", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new-address@example.test",
                                  "currentPassword": "ClaveSegura1"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void issuesRefreshAndCsrfCookiesThatAllowRefreshLocally() throws Exception {
        var email = "csrf-login@example.test";
        insertActiveUser(email, false);

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "ClaveSegura1"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        var cookies = login.getHeaders(HttpHeaders.SET_COOKIE);
        var refresh = cookieValue(cookies, "cv_refresh");
        var csrf = cookieValue(cookies, "XSRF-TOKEN");
        assertTrue(cookies.stream().anyMatch(value -> value.startsWith("cv_refresh=") && value.contains("HttpOnly") && value.contains("SameSite=Lax")));
        assertTrue(cookies.stream().anyMatch(value -> value.startsWith("XSRF-TOKEN=") && value.contains("SameSite=Lax") && !value.contains("HttpOnly")));
        assertFalse(cookies.stream().anyMatch(value -> value.contains("Secure")));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("cv_refresh", refresh), new Cookie("XSRF-TOKEN", csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isOk());
    }

    @Test
    void actionTokensAreSingleUseAndAreAudited() throws Exception {
        var userId = insertUser("single-use-token@example.test", "PENDING_VERIFICATION", false);
        var rawToken = "single-use-action-token";
        insertActionToken(userId, rawToken, "EMAIL_VERIFICATION", null, Instant.now().plusSeconds(300));

        mockMvc.perform(post("/api/v1/auth/email-verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(rawToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/email-verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(rawToken)))
                .andExpect(status().isBadRequest());

        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='EMAIL_VERIFIED' and target_id=?", Long.class, userId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from account_action_token where token_hash=? and consumed_at is not null", Long.class, hash(rawToken)));
    }

    @Test
    void rejectsExpiredActionTokensWithoutChangingTheAccount() throws Exception {
        var userId = insertUser("expired-token@example.test", "PENDING_VERIFICATION", false);
        var rawToken = "expired-action-token";
        insertActionToken(userId, rawToken, "EMAIL_VERIFICATION", null, Instant.now().minusSeconds(1));

        mockMvc.perform(post("/api/v1/auth/email-verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(rawToken)))
                .andExpect(status().isBadRequest());

        assertEquals("PENDING_VERIFICATION", jdbc.queryForObject("select status from user_account where id=?", String.class, userId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from audit_event where action='EMAIL_VERIFIED' and target_id=?", Long.class, userId));
    }

    @Test
    void consumesAnActionTokenOnlyOnceWhenConfirmedConcurrently() throws Exception {
        var userId = insertUser("concurrent-token@example.test", "PENDING_VERIFICATION", false);
        var rawToken = "concurrent-action-token";
        insertActionToken(userId, rawToken, "EMAIL_VERIFICATION", null, Instant.now().plusSeconds(300));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> confirm = () -> {
            ready.countDown();
            start.await();
            try {
                identityService.confirm(rawToken, "EMAIL_VERIFICATION", null);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(confirm);
            var second = executor.submit(confirm);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1L, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }

        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='EMAIL_VERIFIED' and target_id=?", Long.class, userId));
    }

    @Test
    void returnsConflictWhenTheRequestedEmailAlreadyBelongsToAnotherAccount() throws Exception {
        insertActiveUser("existing-email@example.test", false);
        var userId = insertActiveUser("email-change-owner@example.test", false);
        var rawToken = "email-change-conflict";
        insertActionToken(userId, rawToken, "EMAIL_CHANGE", "existing-email@example.test", Instant.now().plusSeconds(300));

        mockMvc.perform(post("/api/v1/auth/email-change/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(rawToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void preservesEmailUniquenessWhenConfirmationsRace() throws Exception {
        var firstUser = insertActiveUser("email-race-first@example.test", false);
        var secondUser = insertActiveUser("email-race-second@example.test", false);
        var targetEmail = "email-race-target@example.test";
        var firstToken = "email-race-token-first";
        var secondToken = "email-race-token-second";
        insertActionToken(firstUser, firstToken, "EMAIL_CHANGE", targetEmail, Instant.now().plusSeconds(300));
        insertActionToken(secondUser, secondToken, "EMAIL_CHANGE", targetEmail, Instant.now().plusSeconds(300));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> confirmFirst = confirmEmailChange(firstToken, ready, start);
        Callable<Boolean> confirmSecond = confirmEmailChange(secondToken, ready, start);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(confirmFirst);
            var second = executor.submit(confirmSecond);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1L, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }

        assertEquals(1L, jdbc.queryForObject("select count(*) from user_account where email_normalized=?", Long.class, targetEmail));
    }

    @Test
    void locksAnAccountAfterFiveFailedLoginsAndAuditsTheLock() throws Exception {
        var email = "locked-account@example.test";
        var userId = insertActiveUser(email, false);

        for (var attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"Incorrecta1\"}".formatted(email)))
                    .andExpect(status().isUnauthorized());
        }

        assertTrue(jdbc.queryForObject("select locked_until is not null from user_account where id=?", Boolean.class, userId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='LOGIN_LOCKED' and target_id=?", Long.class, userId));
    }

    @Test
    void rejectsMissingCsrfAndRevokesAllSessionsWhenARefreshTokenIsReused() throws Exception {
        var email = "refresh-reuse@example.test";
        var userId = insertActiveUser(email, false);
        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"ClaveSegura1\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        var cookies = login.getHeaders(HttpHeaders.SET_COOKIE);
        var refresh = cookieValue(cookies, "cv_refresh");
        var csrf = cookieValue(cookies, "XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("cv_refresh", refresh), new Cookie("XSRF-TOKEN", csrf)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("cv_refresh", refresh), new Cookie("XSRF-TOKEN", csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("cv_refresh", refresh), new Cookie("XSRF-TOKEN", csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isUnauthorized());

        assertEquals(0L, jdbc.queryForObject("select count(*) from user_session where user_id=? and revoked_at is null", Long.class, userId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='REFRESH_ROTATED' and target_id=?", Long.class, userId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='REFRESH_TOKEN_REUSE' and target_id=?", Long.class, userId));
    }

    @Test
    void logsOutWithCsrfAndRecordsAnAuditEvent() throws Exception {
        var email = "logout-audit@example.test";
        var userId = insertActiveUser(email, false);
        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"ClaveSegura1\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        var cookies = login.getHeaders(HttpHeaders.SET_COOKIE);
        var refresh = cookieValue(cookies, "cv_refresh");
        var csrf = cookieValue(cookies, "XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("cv_refresh", refresh), new Cookie("XSRF-TOKEN", csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isNoContent());

        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='LOGOUT' and target_id=?", Long.class, userId));
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Recruiter Test",
                                  "email": "%s",
                                  "password": "ClaveSegura1"
                                }
                                """.formatted(email)))
                .andExpect(status().isAccepted());
    }

    private UUID insertActiveUser(String email, boolean forcePasswordChange) {
        return insertUser(email, "ACTIVE", forcePasswordChange);
    }

    private UUID insertUser(String email, String status, boolean forcePasswordChange) {
        var id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,'RECRUITER',?,?,?,?,?)",
                id,
                "Recruiter Test",
                email,
                email,
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("ClaveSegura1"),
                status,
                "ACTIVE".equals(status) ? now : null,
                forcePasswordChange,
                now,
                now
        );
        return id;
    }

    private void insertActionToken(UUID userId, String rawToken, String purpose, String targetEmail, Instant expiresAt) {
        jdbc.update(
                "insert into account_action_token(id,user_id,token_hash,purpose,target_email,expires_at,created_at) values(?,?,?,?,?,?,?)",
                UUID.randomUUID(), userId, hash(rawToken), purpose, targetEmail, Timestamp.from(expiresAt), Timestamp.from(Instant.now())
        );
    }

    private Callable<Boolean> confirmEmailChange(String rawToken, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                identityService.confirm(rawToken, "EMAIL_CHANGE", null);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        };
    }

    private String hash(String value) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String cookieValue(List<String> cookies, String name) {
        return cookies.stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
                .orElseThrow();
    }
}
