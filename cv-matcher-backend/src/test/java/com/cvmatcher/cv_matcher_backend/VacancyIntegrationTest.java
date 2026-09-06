package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyException;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VacancyIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private VacancyService vacancies;
    @Autowired private MeterRegistry metrics;

    @Test
    void requiresAnActivePersistedRecruiterOrAdministratorSession() throws Exception {
        var recruiter = insertUser("vacancy-recruiter@example.test", "RECRUITER");
        var session = insertSession(recruiter, "vacancy-recruiter-session");

        mockMvc.perform(get("/api/v1/vacancies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/vacancies").header("Authorization", bearer(recruiter, "ADMIN", session)))
                .andExpect(status().isOk());

        jdbc.update("update user_session set revoked_at=? where id=?", Timestamp.from(Instant.now()), session);
        mockMvc.perform(get("/api/v1/vacancies").header("Authorization", bearer(recruiter, "RECRUITER", session)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsACompleteVacancyAndPersistsLaPazBoundsInUtc() throws Exception {
        var actor = insertUser("vacancy-create@example.test", "RECRUITER");
        var session = insertSession(actor, "vacancy-create-session");

        mockMvc.perform(post("/api/v1/vacancies").header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content(request("2026-09-01", "2026-09-15", 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.requirements.length()").value(2))
                .andExpect(jsonPath("$.requirements[0].position").value(0));

        var vacancyId = jdbc.queryForObject("select target_id from audit_event where action='VACANCY_CREATED' and actor_user_id=? order by created_at desc limit 1", UUID.class, actor);
        var from = jdbc.queryForObject("select received_from_utc from vacancy where id=?", Timestamp.class, vacancyId);
        var to = jdbc.queryForObject("select received_to_utc_exclusive from vacancy where id=?", Timestamp.class, vacancyId);
        assertEquals(Instant.parse("2026-09-01T04:00:00Z"), from.toInstant());
        assertEquals(Instant.parse("2026-09-16T04:00:00Z"), to.toInstant());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='VACANCY_CREATED' and target_id=?", Long.class, vacancyId));
    }

    @Test
    void validatesPayloadsAndDoesNotPersistPartialVacancies() throws Exception {
        var actor = insertUser("vacancy-validation@example.test", "ADMIN");
        var session = insertSession(actor, "vacancy-validation-session");

        mockMvc.perform(post("/api/v1/vacancies").header("Authorization", bearer(actor, "ADMIN", session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"description\":\"x\",\"dateFrom\":\"2026-09-02\",\"dateTo\":\"2026-09-01\",\"expectedVersion\":0,\"requirements\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/vacancies").header("Authorization", bearer(actor, "ADMIN", session))
                        .contentType(MediaType.APPLICATION_JSON).content(requestWithRequirements(1, 6)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/vacancies").header("Authorization", bearer(actor, "ADMIN", session))
                        .contentType(MediaType.APPLICATION_JSON).content(requestWithRequirements(31, 3)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/vacancies").header("Authorization", bearer(actor, "ADMIN", session))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Backend Java Senior","description":"Construir servicios Java.","dateFrom":"2026-09-01","dateTo":"2026-09-01","expectedVersion":0,"requirements":[{"description":"Java","weight":5,"mandatory":true}],"ownerId":"ignored"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from vacancy", Long.class));
    }

    @Test
    void listsSharedVacanciesAndArchivesAndReactivatesIdempotently() throws Exception {
        var actor = insertUser("vacancy-state@example.test", "RECRUITER");
        var session = insertSession(actor, "vacancy-state-session");
        var archived = vacancies.create(actor, command(0));
        var active = vacancies.create(actor, new VacancyService.VacancyCommand("Analista de datos", "Construir tableros.", LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-16"), 0,
                List.of(new VacancyService.RequirementCommand("SQL", 4, true))));
        var alreadyArchived = vacancies.create(actor, new VacancyService.VacancyCommand("QA Automation", "Asegurar calidad.", LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-17"), 0,
                List.of(new VacancyService.RequirementCommand("Pruebas", 4, true))));
        vacancies.archive(actor, alreadyArchived.id(), 0);

        var expectedActiveOrder = jdbc.queryForList("select id::text from vacancy where status='ACTIVE' order by updated_at desc,id asc", String.class);
        mockMvc.perform(get("/api/v1/vacancies").header("Authorization", bearer(actor, "RECRUITER", session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(expectedActiveOrder.size()))
                .andExpect(jsonPath("$.items[0].description").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").value(expectedActiveOrder.get(0)))
                .andExpect(jsonPath("$.items[1].id").value(expectedActiveOrder.get(1)));
        var archiveMetricBefore = mutationCount("archive", "success");
        mockMvc.perform(post("/api/v1/vacancies/{id}/archive", archived.id()).header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isNoContent());
        var archiveMetricAfterFirstTransition = mutationCount("archive", "success");
        assertEquals(archiveMetricBefore + 1, archiveMetricAfterFirstTransition);
        var expectedActiveAfterArchive = jdbc.queryForList("select id::text from vacancy where status='ACTIVE' order by updated_at desc,id asc", String.class);
        mockMvc.perform(get("/api/v1/vacancies").header("Authorization", bearer(actor, "RECRUITER", session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(expectedActiveAfterArchive.size()))
                .andExpect(jsonPath("$.items[0].id").value(expectedActiveAfterArchive.get(0)));
        var expectedArchivedOrder = jdbc.queryForList("select id::text from vacancy where status='ARCHIVED' order by updated_at desc,id asc", String.class);
        mockMvc.perform(get("/api/v1/vacancies?status=ARCHIVED&page=0&size=1").header("Authorization", bearer(actor, "RECRUITER", session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(expectedArchivedOrder.size()))
                .andExpect(jsonPath("$.totalPages").value(expectedArchivedOrder.size()))
                .andExpect(jsonPath("$.items[0].id").value(expectedArchivedOrder.get(0)));
        mockMvc.perform(get("/api/v1/vacancies?status=ARCHIVED&page=1&size=1").header("Authorization", bearer(actor, "RECRUITER", session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(expectedArchivedOrder.get(1)));
        mockMvc.perform(post("/api/v1/vacancies/{id}/archive", archived.id()).header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isNoContent());
        assertEquals(1L, jdbc.queryForObject("select version from vacancy where id=?", Long.class, archived.id()));
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='VACANCY_ARCHIVED' and target_id=?", Long.class, archived.id()));
        assertEquals(archiveMetricAfterFirstTransition, mutationCount("archive", "success"));
        mockMvc.perform(post("/api/v1/vacancies/{id}/reactivate", archived.id()).header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/vacancies/{id}", archived.id()).header("Authorization", bearer(actor, "RECRUITER", session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requirements.length()").value(2));
    }

    @Test
    void updatesRequirementsAtomicallyAndRejectsArchivedAndStaleWrites() throws Exception {
        var actor = insertUser("vacancy-update@example.test", "RECRUITER");
        var session = insertSession(actor, "vacancy-update-session");
        var created = vacancies.create(actor, command(0));

        mockMvc.perform(put("/api/v1/vacancies/{id}", created.id()).header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content(request("2026-09-03", "2026-09-04", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertEquals(2L, jdbc.queryForObject("select count(*) from vacancy_requirement where vacancy_id=?", Long.class, created.id()));
        mockMvc.perform(put("/api/v1/vacancies/{id}", created.id()).header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content(request("2026-09-03", "2026-09-04", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/v1/vacancies/{id}/archive", created.id()).header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/vacancies/{id}", created.id()).header("Authorization", bearer(actor, "RECRUITER", session))
                        .contentType(MediaType.APPLICATION_JSON).content(request("2026-09-03", "2026-09-04", 2)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VACANCY_ARCHIVED"));
    }

    @Test
    void allowsOnlyOneConcurrentUpdateForTheSameVersion() throws Exception {
        var actor = insertUser("vacancy-race@example.test", "ADMIN");
        var created = vacancies.create(actor, command(0));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> update = () -> {
            ready.countDown();
            start.await();
            try {
                vacancies.update(actor, created.id(), command(0));
                return true;
            } catch (VacancyException exception) {
                assertEquals("VERSION_CONFLICT", exception.code());
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(update);
            var second = executor.submit(update);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1L, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1L, jdbc.queryForObject("select version from vacancy where id=?", Long.class, created.id()));
        assertTrue(metrics.find("vacancy.mutations").tag("action", "update").tag("outcome", "success").counter().count() >= 1);
    }

    private VacancyService.VacancyCommand command(long version) {
        return new VacancyService.VacancyCommand("Backend Java Senior", "Construir servicios Java.", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"), version,
                List.of(new VacancyService.RequirementCommand("Java", 5, true), new VacancyService.RequirementCommand("PostgreSQL", 3, false)));
    }

    private String request(String from, String to, long version) {
        return """
                {"title":"Backend Java Senior","description":"Construir servicios Java.","dateFrom":"%s","dateTo":"%s","expectedVersion":%d,"requirements":[{"description":"Java","weight":5,"mandatory":true},{"description":"PostgreSQL","weight":3,"mandatory":false}]}
                """.formatted(from, to, version);
    }

    private String requestWithRequirements(int requirementCount, int weight) {
        var requirements = java.util.stream.IntStream.range(0, requirementCount)
                .mapToObj(position -> "{\"description\":\"Requisito %d\",\"weight\":%d,\"mandatory\":true}".formatted(position, weight))
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"title\":\"Backend Java Senior\",\"description\":\"Construir servicios Java.\",\"dateFrom\":\"2026-09-01\",\"dateTo\":\"2026-09-15\",\"expectedVersion\":0,\"requirements\":[%s]}".formatted(requirements);
    }

    private double mutationCount(String action, String outcome) {
        var counter = metrics.find("vacancy.mutations").tag("action", action).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private UUID insertUser(String email, String role) {
        var id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,?, 'ACTIVE',?,false,?,?)",
                id, email, email, email, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("ClaveSegura1"), role, now, now, now);
        return id;
    }

    private UUID insertSession(UUID userId, String rawToken) throws Exception {
        var id = UUID.randomUUID();
        jdbc.update("insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)", id, userId, hash(rawToken),
                Timestamp.from(Instant.now().plusSeconds(300)), Timestamp.from(Instant.now()));
        return id;
    }

    private String bearer(UUID userId, String role, UUID sessionId) { return "Bearer " + jwt.issue(userId, role, sessionId); }

    private String hash(String value) throws Exception {
        return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
