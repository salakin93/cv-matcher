package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import com.cvmatcher.cv_matcher_backend.job.application.JobException;
import com.cvmatcher.cv_matcher_backend.job.application.JobService;
import com.cvmatcher.cv_matcher_backend.job.application.MatchingJobWorkerPort;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JobIntegrationTest {
    @Autowired private JobService jobs;
    @Autowired private VacancyService vacancies;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwt;
    @Autowired private MeterRegistry metrics;
    @Autowired private MatchingJobWorkerPort worker;
    private final Set<UUID> createdUsers = ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanUp() {
        for (var userId : createdUsers) {
            var vacancyIds = jdbc.query("select target_id from audit_event where actor_user_id=? and target_type='VACANCY' and target_id is not null",
                    (rs, ignored) -> UUID.fromString(rs.getString("target_id")), userId);
            jdbc.update("delete from matching_job_event where matching_job_id in (select id from matching_job where requested_by_user_id=?)", userId);
            jdbc.update("delete from matching_job_requirement where matching_job_id in (select id from matching_job where requested_by_user_id=?)", userId);
            jdbc.update("delete from matching_job where requested_by_user_id=?", userId);
            for (var vacancyId : vacancyIds) {
                jdbc.update("delete from vacancy_requirement where vacancy_id=?", vacancyId);
                jdbc.update("delete from vacancy where id=?", vacancyId);
            }
            jdbc.update("delete from audit_event where actor_user_id=?", userId);
            jdbc.update("delete from account_action_token where user_id=?", userId);
            jdbc.update("delete from user_session where user_id=?", userId);
            jdbc.update("delete from user_account where id=?", userId);
        }
        createdUsers.clear();
    }

    @Test
    void enqueuesAnImmutableSnapshotAndCancelsIdempotently() {
        var actor = insertUser("jobs-snapshot@example.test", "RECRUITER");
        var vacancy = vacancies.create(actor, command(0));
        var accepted = jobs.enqueue(actor, vacancy.id());
        assertEquals(1.0, activeJobs("queued"));
        vacancies.update(actor, vacancy.id(), new VacancyService.VacancyCommand("Nuevo título", "Nueva descripción", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), 0, List.of(new VacancyService.RequirementCommand("Kotlin", 1, true))));

        var detail = jobs.get(accepted.jobId());
        assertEquals("Backend Java Senior", detail.vacancyTitle());
        assertEquals(2, detail.requirements().size());
        jobs.cancel(actor, accepted.jobId());
        assertEquals(0.0, activeJobs("queued"));
        var firstFinishedAt = jobs.get(accepted.jobId()).finishedAt();
        jobs.cancel(actor, accepted.jobId());
        assertEquals(firstFinishedAt, jobs.get(accepted.jobId()).finishedAt());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_event where action='REPORT_JOB_CANCELLED' and target_id=?", Long.class, accepted.jobId()));
    }

    @Test
    void concurrentEnqueuesLeaveExactlyOneActiveJobAndRetryCopiesSnapshot() throws Exception {
        var actor = insertUser("jobs-race@example.test", "ADMIN");
        var vacancy = vacancies.create(actor, command(0));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attemptEnqueue(actor, vacancy.id(), ready, start));
            var second = executor.submit(() -> attemptEnqueue(actor, vacancy.id(), ready, start));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(first.get(), second.get());
            assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1L, jdbc.queryForObject("select count(*) from matching_job where vacancy_id=? and status='QUEUED'", Long.class, vacancy.id()));
        assertTrue(queueRequests("accepted") >= 1.0);
        assertTrue(queueRequests("active_conflict") >= 1.0);
        assertTrue(mutations("enqueue", "conflict") >= 1.0);
        var original = jdbc.queryForObject("select id from matching_job where vacancy_id=?", UUID.class, vacancy.id());
        jdbc.update("update matching_job set status='FAILED', finished_at=?, updated_at=? where id=?", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), original);
        var retry = jobs.retry(actor, original);
        assertEquals(2, retry.attempt());
        assertEquals(original, jdbc.queryForObject("select retry_of_job_id from matching_job where id=?", UUID.class, retry.jobId()));
    }

    @Test
    void reportJobEndpointsRequireRecruiterOrAdmin() throws Exception {
        var actor = insertUser("jobs-http@example.test", "RECRUITER");
        var session = insertSession(actor);
        var vacancy = vacancies.create(actor, command(0));
        mockMvc.perform(get("/api/v1/vacancies/{id}/report-jobs", vacancy.id()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/vacancies/{id}/report-jobs", vacancy.id()).header("Authorization", "Bearer " + jwt.issue(actor, "RECRUITER", session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void exposesTheReportJobHttpContractIncludingPagingAndSafeErrors() throws Exception {
        var actor = insertUser("jobs-contract@example.test", "RECRUITER");
        var session = insertSession(actor);
        var token = "Bearer " + jwt.issue(actor, "RECRUITER", session);
        var vacancy = vacancies.create(actor, command(0));

        mockMvc.perform(post("/api/v1/vacancies/{id}/report-jobs", vacancy.id()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.attempt").value(1)).andExpect(jsonPath("$.statusUrl").exists());
        var firstJob = jdbc.queryForObject("select id from matching_job where vacancy_id=?", UUID.class, vacancy.id());
        mockMvc.perform(post("/api/v1/vacancies/{id}/report-jobs", vacancy.id()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"unexpected\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/vacancies/{id}/report-jobs", vacancy.id()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertTrue(mutations("enqueue", "validation_error") >= 1.0);
        mockMvc.perform(post("/api/v1/vacancies/{id}/report-jobs", vacancy.id()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/report-jobs/{id}", firstJob).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(firstJob.toString())).andExpect(jsonPath("$.requirements.length()").value(2));

        jdbc.update("update matching_job set status='FAILED',finished_at=?,updated_at=? where id=?", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), firstJob);
        var secondJob = jobs.retry(actor, firstJob).jobId();
        jobs.cancel(actor, secondJob);
        var thirdJob = jobs.retry(actor, firstJob).jobId();
        jobs.cancel(actor, thirdJob);
        var expected = jdbc.queryForList("select id::text from matching_job where vacancy_id=? order by created_at desc,id asc", String.class, vacancy.id());
        mockMvc.perform(get("/api/v1/vacancies/{id}/report-jobs?page=0&size=1", vacancy.id()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3)).andExpect(jsonPath("$.items[0].id").value(expected.get(0)));
        mockMvc.perform(get("/api/v1/vacancies/{id}/report-jobs?status=FAILED", vacancy.id()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(firstJob.toString()));
        var cancelConflictsBefore = mutations("cancel", "conflict");
        mockMvc.perform(post("/api/v1/report-jobs/{id}/cancel", firstJob).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_NOT_CANCELLABLE"));
        assertEquals(cancelConflictsBefore + 1, mutations("cancel", "conflict"));
        mockMvc.perform(post("/api/v1/report-jobs/{id}/cancel", firstJob).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        var retryConflictsBefore = mutations("retry", "conflict");
        mockMvc.perform(post("/api/v1/report-jobs/{id}/retry", thirdJob).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_NOT_RETRYABLE"));
        assertEquals(retryConflictsBefore + 1, mutations("retry", "conflict"));
        mockMvc.perform(post("/api/v1/report-jobs/{id}/retry", thirdJob).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/vacancies/{id}/report-jobs", UUID.randomUUID()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VACANCY_NOT_FOUND"));
        var archived = vacancies.create(actor, command(0));
        vacancies.archive(actor, archived.id(), 0);
        var enqueueConflictsBefore = mutations("enqueue", "conflict");
        mockMvc.perform(post("/api/v1/vacancies/{id}/report-jobs", archived.id()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VACANCY_ARCHIVED"));
        assertEquals(enqueueConflictsBefore + 1, mutations("enqueue", "conflict"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void rejectsAuthenticatedRolesOutsideRecruiterAndAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/report-jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void publishesTheCompleteReportJobApiContractInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/vacancies/{vacancyId}/report-jobs")))
                .andExpect(content().string(containsString("/api/v1/report-jobs/{jobId}/cancel")))
                .andExpect(content().string(containsString("ACTIVE_JOB_EXISTS")))
                .andExpect(content().string(containsString("\"204\"")));
    }

    @Test
    void claimsOnlyOnceRecoversExpiredLeasesAndFinishesOnlyOnce() throws Exception {
        var actor = insertUser("jobs-lease@example.test", "RECRUITER");
        var vacancy = vacancies.create(actor, command(0));
        var accepted = jobs.enqueue(actor, vacancy.id());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claim("worker-a", ready, start));
            var second = executor.submit(() -> claim("worker-b", ready, start));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var claims = List.of(first.get(), second.get());
            assertEquals(1L, claims.stream().filter(java.util.Optional::isPresent).count());
        }
        assertEquals("DISCOVERING", jdbc.queryForObject("select status from matching_job where id=?", String.class, accepted.jobId()));
        jdbc.update("update matching_job set lease_until=? where id=?", Timestamp.from(Instant.now().minusSeconds(1)), accepted.jobId());

        var recovered = worker.claimNext("worker-c", Duration.ofMinutes(1));
        assertTrue(recovered.isPresent());
        assertEquals(accepted.jobId(), recovered.get().jobId());
        assertTrue(worker.transitionClaimed(accepted.jobId(), "worker-c", JobService.Status.INGESTING_DOCUMENTS));
        assertTrue(worker.transitionClaimed(accepted.jobId(), "worker-c", JobService.Status.ANALYZING));
        assertTrue(!worker.transitionClaimed(accepted.jobId(), "worker-c", JobService.Status.INGESTING_DOCUMENTS));
        assertThrows(IllegalArgumentException.class, () -> worker.finishClaimed(accepted.jobId(), "worker-c", JobService.Status.FAILED, "unsafe failure"));
        assertThrows(IllegalArgumentException.class, () -> worker.finishClaimed(accepted.jobId(), "worker-c", JobService.Status.FAILED, "A".repeat(81)));
        assertTrue(worker.finishClaimed(accepted.jobId(), "worker-c", JobService.Status.FAILED, "SAFE_FAILURE"));
        assertTrue(!worker.finishClaimed(accepted.jobId(), "worker-c", JobService.Status.FAILED, "SAFE_FAILURE"));
        assertEquals("FAILED", jdbc.queryForObject("select status from matching_job where id=?", String.class, accepted.jobId()));
        assertEquals("QUEUED", jdbc.queryForObject("select from_status from matching_job_event where matching_job_id=? and action='CLAIMED' order by created_at asc,id asc limit 1", String.class, accepted.jobId()));
        assertEquals("ANALYZING", jdbc.queryForObject("select from_status from matching_job_event where matching_job_id=? and action='TERMINATED'", String.class, accepted.jobId()));
        assertEquals(1L, jdbc.queryForObject("select count(*) from matching_job_event where matching_job_id=? and action='TERMINATED'", Long.class, accepted.jobId()));
    }

    private boolean attemptEnqueue(UUID actor, UUID vacancyId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await();
        try { jobs.enqueue(actor, vacancyId); return true; }
        catch (JobException exception) { assertEquals("ACTIVE_JOB_EXISTS", exception.code()); return false; }
    }
    private java.util.Optional<JobService.ClaimedJob> claim(String workerId, CountDownLatch ready, CountDownLatch start) throws Exception { ready.countDown(); start.await(); return worker.claimNext(workerId, Duration.ofMinutes(1)); }
    private double activeJobs(String status) { return metrics.find("matching_jobs.active").tag("status", status).gauge().value(); }
    private double queueRequests(String result) { return metrics.find("matching_jobs.queue_requests").tag("result", result).counter().count(); }
    private double mutations(String action, String outcome) { var counter = metrics.find("matching_jobs.mutations").tag("action", action).tag("outcome", outcome).counter(); return counter == null ? 0 : counter.count(); }
    private VacancyService.VacancyCommand command(long version) { return new VacancyService.VacancyCommand("Backend Java Senior", "Construir servicios Java.", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"), version, List.of(new VacancyService.RequirementCommand("Java", 5, true), new VacancyService.RequirementCommand("PostgreSQL", 3, false))); }
    private UUID insertUser(String email, String role) { var id = UUID.randomUUID(); var now = Timestamp.from(Instant.now()); jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,?, 'ACTIVE',?,false,?,?)", id, email, email, email, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("ClaveSegura1"), role, now, now, now); createdUsers.add(id); return id; }
    private UUID insertSession(UUID userId) throws Exception { var id = UUID.randomUUID(); jdbc.update("insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)", id, userId, java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest("job-session".getBytes(java.nio.charset.StandardCharsets.UTF_8))), Timestamp.from(Instant.now().plusSeconds(300)), Timestamp.from(Instant.now())); return id; }
}
