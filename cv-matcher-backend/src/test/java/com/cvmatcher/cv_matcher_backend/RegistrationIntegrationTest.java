package com.cvmatcher.cv_matcher_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

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
}
