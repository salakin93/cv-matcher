package com.cvmatcher.cv_matcher_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;

import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobEventRepository;
import com.cvmatcher.cv_matcher_backend.matchingjob.repository.MatchingJobRepository;
import com.cvmatcher.cv_matcher_backend.matchingjob.service.MatchingJobConflictException;
import com.cvmatcher.cv_matcher_backend.microsoft.domain.MicrosoftOAuthConnection;
import com.cvmatcher.cv_matcher_backend.microsoft.repository.MicrosoftOAuthConnectionRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MatchingJobControllerIntegrationTest.ConflictThrowingController.class})
@TestPropertySource(properties = "admin.api-token=test-admin-token")
class MatchingJobControllerIntegrationTest {

    private static final String TOKEN_HEADER = "X-Admin-Token";
    private static final String TOKEN_VALUE = "test-admin-token";
    private static final String REQUEST = """
            {
              "title":"Inspector SIMA",
              "description":"Vacancy description",
              "requirements":[{"description":"Industrial engineering","weight":5,"mandatory":true}],
              "from":"2026-08-01T00:00:00Z",
              "to":"2026-08-31T00:00:00Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MatchingJobRepository matchingJobRepository;

    @Autowired
    private MatchingJobEventRepository matchingJobEventRepository;

    @Autowired
    private MicrosoftOAuthConnectionRepository microsoftOAuthConnectionRepository;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanDatabase() {
        matchingJobEventRepository.deleteAll();
        matchingJobRepository.deleteAll();
        microsoftOAuthConnectionRepository.deleteAll();
        microsoftOAuthConnectionRepository.save(new MicrosoftOAuthConnection(new byte[] {1}, new byte[] {1}, "v1"));
    }

    @Test
    void createsAndRetrievesQueuedJob() throws Exception {
        String response = mockMvc.perform(post("/api/matching-jobs").with(csrf())
                        .header(TOKEN_HEADER, TOKEN_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/matching-jobs/")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.statusUrl").value(org.hamcrest.Matchers.startsWith("/api/matching-jobs/")))
                .andReturn().getResponse().getContentAsString();

        String jobId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("jobId").asText();
        mockMvc.perform(get("/api/matching-jobs/{jobId}", jobId).header(TOKEN_HEADER, TOKEN_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusUrl").value("/api/matching-jobs/" + jobId));
        assertThat(matchingJobEventRepository.findAll())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getMatchingJobId()).isEqualTo(UUID.fromString(jobId));
                    assertThat(event.getNewStatus().name()).isEqualTo("QUEUED");
                    assertThat(event.getEventType()).isEqualTo("JOB_CREATED");
                });
    }

    @Test
    void rejectsMissingTokenUnknownJobAndConcurrentJob() throws Exception {
        mockMvc.perform(post("/api/matching-jobs").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/matching-jobs/{jobId}", java.util.UUID.randomUUID()).header(TOKEN_HEADER, TOKEN_VALUE))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/matching-jobs").with(csrf()).header(TOKEN_HEADER, TOKEN_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/matching-jobs").with(csrf()).header(TOKEN_HEADER, TOKEN_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.activeJobId").isNotEmpty())
                .andExpect(jsonPath("$.statusUrl").value(org.hamcrest.Matchers.startsWith("/api/matching-jobs/")));
    }

    @Test
    void keepsSingleActiveJobWhenCreateRequestsArriveConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        try {
            Callable<MvcResult> createJob = () -> {
                startBarrier.await();
                return mockMvc.perform(post("/api/matching-jobs").with(csrf())
                                .header(TOKEN_HEADER, TOKEN_VALUE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST))
                        .andReturn();
            };

            Future<MvcResult> first = executor.submit(createJob);
            Future<MvcResult> second = executor.submit(createJob);
            List<MvcResult> results = List.of(first.get(), second.get());

            assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
                    .containsExactlyInAnyOrder(202, 409);
            MvcResult conflict = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            var conflictBody = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(conflict.getResponse().getContentAsString());
            assertThat(conflictBody.path("activeJobId").asText()).isNotBlank();
            assertThat(conflictBody.path("statusUrl").asText())
                    .isEqualTo("/api/matching-jobs/" + conflictBody.path("activeJobId").asText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void resolvesConflictWithoutIdToActiveJobReference() throws Exception {
        mockMvc.perform(post("/api/matching-jobs").with(csrf()).header(TOKEN_HEADER, TOKEN_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/test/race-conflict").header(TOKEN_HEADER, TOKEN_VALUE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.activeJobId").isNotEmpty())
                .andExpect(jsonPath("$.statusUrl").value(org.hamcrest.Matchers.startsWith("/api/matching-jobs/")));
    }

    @Test
    void postgresPartialUniqueIndexRejectsConcurrentActiveJobs() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        try {
            Callable<Boolean> insertQueuedJob = () -> insertQueuedJobAfterBarrier(startBarrier);
            Future<Boolean> first = executor.submit(insertQueuedJob);
            Future<Boolean> second = executor.submit(insertQueuedJob);

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean insertQueuedJobAfterBarrier(CyclicBarrier startBarrier) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            startBarrier.await();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO matching_job (
                        id, title, description, from_timestamp, to_timestamp, status, job_mode,
                        correlation_id, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, "Concurrent job");
                statement.setString(3, "Concurrent job test");
                statement.setObject(4, now);
                statement.setObject(5, now);
                statement.setString(6, "QUEUED");
                statement.setString(7, "FULL");
                statement.setString(8, UUID.randomUUID().toString());
                statement.setObject(9, now);
                statement.setObject(10, now);
                statement.executeUpdate();
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) {
                    return false;
                }
                throw exception;
            }
        }
    }

    @RestController
    static class ConflictThrowingController {

        @GetMapping("/api/test/race-conflict")
        void throwRaceConflict() {
            throw new MatchingJobConflictException(null);
        }
    }
}
