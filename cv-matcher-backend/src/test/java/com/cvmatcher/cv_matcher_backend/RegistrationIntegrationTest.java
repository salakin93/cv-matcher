package com.cvmatcher.cv_matcher_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
}
