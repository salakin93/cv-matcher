package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

    private void insertActiveUser(String email, boolean forcePasswordChange) {
        var now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,'RECRUITER','ACTIVE',?,?,?,?)",
                UUID.randomUUID(),
                "Recruiter Test",
                email,
                email,
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("ClaveSegura1"),
                now,
                forcePasswordChange,
                now,
                now
        );
    }

    private String cookieValue(List<String> cookies, String name) {
        return cookies.stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
                .orElseThrow();
    }
}
