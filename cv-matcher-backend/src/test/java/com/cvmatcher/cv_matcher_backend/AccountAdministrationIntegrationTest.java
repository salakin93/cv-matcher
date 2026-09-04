package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.administration.application.AccountAdministrationService;
import com.cvmatcher.cv_matcher_backend.administration.application.AdministrationException;
import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountAdministrationIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwt;

    @Autowired
    private AccountAdministrationService administration;

    @Autowired
    private MeterRegistry metrics;

    @Test
    void restrictsAdministrativeListingToAdministratorsAndHidesSensitiveFields() throws Exception {
        var admin = insertUser("admin-list@example.test", "ADMIN", "ACTIVE");
        var recruiter = insertUser("recruiter-list@example.test", "RECRUITER", "ACTIVE");
        var adminSession = insertSession(admin, "admin-list-session");
        var recruiterSession = insertSession(recruiter, "recruiter-list-session");

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(recruiter, "RECRUITER", recruiterSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/users?role=RECRUITER&status=ACTIVE&page=0&size=1").header("Authorization", bearer(admin, "ADMIN", adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].role").value("RECRUITER"))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].lockedUntil").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/users?page=" + Integer.MAX_VALUE + "&size=100").header("Authorization", bearer(admin, "ADMIN", adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void changesRoleRevokesTheTargetsBearerAndAuditsTheAction() throws Exception {
        var admin = insertUser("admin-role-change@example.test", "ADMIN", "ACTIVE");
        var target = insertUser("target-role-change@example.test", "RECRUITER", "ACTIVE");
        var adminSession = insertSession(admin, "admin-role-change-session");
        var targetSession = insertSession(target, "target-role-change-session");

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/role", target)
                        .header("Authorization", bearer(admin, "ADMIN", adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNoContent());

        assertEquals("ADMIN", jdbc.queryForObject("select role from user_account where id=?", String.class, target));
        assertEquals(1L, jdbc.queryForObject("select count(*) from user_session where id=? and revoked_at is not null", Long.class, targetSession));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='USER_ROLE_CHANGED' and actor_user_id=? and target_id=?", Long.class, admin, target));
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(target, "RECRUITER", targetSession)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsSelfAdministrationPendingAccountsAndUnknownFields() throws Exception {
        var admin = insertUser("admin-conflicts@example.test", "ADMIN", "ACTIVE");
        var pending = insertUser("pending-conflicts@example.test", "RECRUITER", "PENDING_VERIFICATION");
        var adminSession = insertSession(admin, "admin-conflicts-session");
        var authorization = bearer(admin, "ADMIN", adminSession);

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/status", admin)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_ADMINISTRATION_FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/admin/users/{userId}/role", pending)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        mockMvc.perform(patch("/api/v1/admin/users/{userId}/status", pending)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"password\":\"not-allowed\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void activatesVerifiedAccountsAndRevokesTheirSessions() throws Exception {
        var admin = insertUser("admin-activate@example.test", "ADMIN", "ACTIVE");
        var target = insertUser("target-activate@example.test", "RECRUITER", "DISABLED");
        var adminSession = insertSession(admin, "admin-activate-session");
        var targetSession = insertSession(target, "target-activate-session");

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/status", target)
                        .header("Authorization", bearer(admin, "ADMIN", adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNoContent());

        assertEquals("ACTIVE", jdbc.queryForObject("select status from user_account where id=?", String.class, target));
        assertEquals(1L, jdbc.queryForObject("select count(*) from user_session where id=? and revoked_at is not null", Long.class, targetSession));
        assertEquals(1L, jdbc.queryForObject("select count(*) from user_session where user_id=?", Long.class, target));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='USER_ENABLED' and actor_user_id=? and target_id=?", Long.class, admin, target));
    }

    @Test
    void leavesSessionsAndAuditUntouchedForIdempotentMutations() throws Exception {
        var admin = insertUser("admin-idempotent@example.test", "ADMIN", "ACTIVE");
        var target = insertUser("target-idempotent@example.test", "RECRUITER", "ACTIVE");
        var adminSession = insertSession(admin, "admin-idempotent-session");
        var targetSession = insertSession(target, "target-idempotent-session");
        var auditBefore = jdbc.queryForObject("select count(*) from audit_event where target_id=?", Long.class, target);

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/role", target)
                        .header("Authorization", bearer(admin, "ADMIN", adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isNoContent());

        assertEquals(0L, jdbc.queryForObject("select count(*) from user_session where id=? and revoked_at is not null", Long.class, targetSession));
        assertEquals(auditBefore, jdbc.queryForObject("select count(*) from audit_event where target_id=?", Long.class, target));
    }

    @Test
    void returnsNotFoundAndValidationErrorsForAdministrativeRequests() throws Exception {
        var admin = insertUser("admin-errors@example.test", "ADMIN", "ACTIVE");
        var adminSession = insertSession(admin, "admin-errors-session");
        var authorization = bearer(admin, "ADMIN", adminSession);

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/role", UUID.randomUUID())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/users?role=UNKNOWN").header("Authorization", authorization))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/admin/users?page=-1&size=101").header("Authorization", authorization))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsChangingTheLastActiveAdministrator() {
        var lastAdmin = insertUser("last-admin@example.test", "ADMIN", "ACTIVE");
        var otherActiveAdmins = jdbc.query(
                "select id from user_account where id<>? and role='ADMIN' and status='ACTIVE'",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                lastAdmin
        );
        otherActiveAdmins.forEach(id -> jdbc.update("update user_account set status='DISABLED' where id=?", id));

        try {
            var exception = assertThrows(AdministrationException.class,
                    () -> administration.changeStatus(UUID.randomUUID(), lastAdmin, AccountAdministrationService.Status.DISABLED));
            assertEquals("LAST_ACTIVE_ADMIN", exception.code());
            assertEquals("ACTIVE", jdbc.queryForObject("select status from user_account where id=?", String.class, lastAdmin));
        } finally {
            otherActiveAdmins.forEach(id -> jdbc.update("update user_account set status='ACTIVE' where id=?", id));
        }
    }

    @Test
    void recordsAdministrativeMetricsAndDocumentsTheApi() throws Exception {
        var admin = insertUser("admin-metrics@example.test", "ADMIN", "ACTIVE");
        var target = insertUser("target-metrics@example.test", "RECRUITER", "ACTIVE");
        var adminSession = insertSession(admin, "admin-metrics-session");
        var authorization = bearer(admin, "ADMIN", adminSession);
        var roleChanges = metricCount("identity.admin.role_changes", "outcome", "success");
        var statusChanges = metricCount("identity.admin.status_changes", "outcome", "success");
        var conflicts = metricCount("identity.admin.conflicts", "code", "SELF_ADMINISTRATION_FORBIDDEN");

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/role", target).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(patch("/api/v1/admin/users/{userId}/status", target).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(patch("/api/v1/admin/users/{userId}/status", admin).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict());

        assertEquals(roleChanges + 1, metricCount("identity.admin.role_changes", "outcome", "success"));
        assertEquals(statusChanges + 1, metricCount("identity.admin.status_changes", "outcome", "success"));
        assertEquals(conflicts + 1, metricCount("identity.admin.conflicts", "code", "SELF_ADMINISTRATION_FORBIDDEN"));
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].get.parameters[0].name").value("role"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].get.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users/{userId}/role'].patch.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users/{userId}/status'].patch.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users/{userId}/status'].patch.responses['422']").exists());
    }

    @Test
    void preservesAnActiveAdministratorWhenConcurrentMutationsRace() throws Exception {
        var firstAdmin = insertUser("first-admin-race@example.test", "ADMIN", "ACTIVE");
        var secondAdmin = insertUser("second-admin-race@example.test", "ADMIN", "ACTIVE");
        var otherActiveAdmins = jdbc.queryForObject(
                "select count(*) from user_account where id not in (?,?) and role='ADMIN' and status='ACTIVE'",
                Long.class,
                firstAdmin,
                secondAdmin
        );
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> disableSecond = disable(firstAdmin, secondAdmin, ready, start);
        Callable<Boolean> disableFirst = disable(secondAdmin, firstAdmin, ready, start);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(disableSecond);
            var second = executor.submit(disableFirst);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(otherActiveAdmins == 0 ? 1L : 2L,
                    java.util.stream.Stream.of(first.get(), second.get()).filter(Boolean::booleanValue).count());
        }

        assertEquals(otherActiveAdmins == 0 ? 1L : 0L,
                jdbc.queryForObject("select count(*) from user_account where id in (?,?) and role='ADMIN' and status='ACTIVE'", Long.class, firstAdmin, secondAdmin));
    }

    private UUID insertUser(String email, String role, String status) {
        var id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                id,
                email,
                email,
                email,
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("ClaveSegura1"),
                role,
                status,
                "PENDING_VERIFICATION".equals(status) ? null : now,
                false,
                now,
                now
        );
        return id;
    }

    private UUID insertSession(UUID userId, String rawToken) throws Exception {
        var id = UUID.randomUUID();
        jdbc.update(
                "insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)",
                id,
                userId,
                java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                Timestamp.from(Instant.now().plusSeconds(300)),
                Timestamp.from(Instant.now())
        );
        return id;
    }

    private String bearer(UUID userId, String role, UUID sessionId) {
        return "Bearer " + jwt.issue(userId, role, sessionId);
    }

    private double metricCount(String name, String tag, String value) {
        var counter = metrics.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private Callable<Boolean> disable(UUID actorId, UUID targetId, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                administration.changeStatus(actorId, targetId, AccountAdministrationService.Status.DISABLED);
                return true;
            } catch (AdministrationException exception) {
                return false;
            }
        };
    }
}
