package com.cvmatcher.cv_matcher_backend.document;

import com.cvmatcher.cv_matcher_backend.TestcontainersConfiguration;
import com.cvmatcher.cv_matcher_backend.job.application.JobService;
import com.cvmatcher.cv_matcher_backend.outlook.application.AttachmentException;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookAttachmentPort;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentIngestionWorkerIntegrationTest {
    private static final byte[] PDF = validPdf();

    @Autowired JobService jobs;
    @Autowired VacancyService vacancies;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentPersistence documents;
    @Autowired MeterRegistry metrics;
    @TempDir Path storageRoot;
    private UUID actorId;
    private UUID jobId;
    private UUID vacancyId;

    @AfterEach
    void cleanUp() {
        if (jobId != null) {
            var documentIds = jdbc.query("select candidate_document_id from matching_job_document where matching_job_id=?", (rs, ignored) -> UUID.fromString(rs.getString(1)), jobId);
            jdbc.update("delete from matching_job_document where matching_job_id=?", jobId);
            for (var documentId : documentIds) jdbc.update("delete from candidate_document where id=?", documentId);
            jdbc.update("delete from matching_job_discovered_message where matching_job_id=?", jobId);
            jdbc.update("delete from matching_job_event where matching_job_id=?", jobId);
            jdbc.update("delete from matching_job_requirement where matching_job_id=?", jobId);
            jdbc.update("delete from audit_event where target_id=?", jobId);
            jdbc.update("delete from matching_job where id=?", jobId);
        }
        if (vacancyId != null) {
            jdbc.update("delete from vacancy_requirement where vacancy_id=?", vacancyId);
            jdbc.update("delete from vacancy where id=?", vacancyId);
        }
        if (actorId != null) {
            jdbc.update("delete from audit_event where actor_user_id=?", actorId);
            jdbc.update("delete from user_account where id=?", actorId);
        }
    }

    @Test
    void acceptsAndEncryptsCleanPdf() throws Exception {
        jobId = ingestionJob("message-clean");
        var worker = worker(singleAttachment("attachment-clean", PDF), AntivirusPort.Result.CLEAN);

        ingest(worker);

        assertEquals("ANALYZING", status());
        assertEquals(1, jobs.get(jobId).acceptedDocumentCount());
        var key = jdbc.queryForObject("select d.storage_key from candidate_document d join matching_job_document j on j.candidate_document_id=d.id where j.matching_job_id=?", String.class, jobId);
        assertTrue(key.endsWith(".gcm"));
        assertFalse(new String(Files.readAllBytes(storageRoot.resolve(key)), StandardCharsets.ISO_8859_1).contains("synthetic"));
    }

    @Test
    void failsWithNoValidDocuments() {
        jobId = ingestionJob("message-invalid");
        var worker = worker(singleAttachment("attachment-invalid", "not a document".getBytes(StandardCharsets.US_ASCII)), AntivirusPort.Result.CLEAN);

        ingest(worker);

        assertEquals("FAILED", status());
        assertEquals("NO_VALID_CV_DOCUMENTS", jobs.get(jobId).failureCode());
        assertEquals(1, jobs.get(jobId).ignoredDocumentCount());
    }

    @Test
    void quarantinesMalwareWithoutAStorageKey() {
        jobId = ingestionJob("message-malware");
        var worker = worker(singleAttachment("attachment-malware", PDF), AntivirusPort.Result.DETECTED);

        ingest(worker);

        assertEquals("FAILED", status());
        assertEquals(1, jobs.get(jobId).quarantinedDocumentCount());
        assertEquals("QUARANTINED", jdbc.queryForObject("select d.status from candidate_document d join matching_job_document j on j.candidate_document_id=d.id where j.matching_job_id=?", String.class, jobId));
        assertNull(jdbc.queryForObject("select d.storage_key from candidate_document d join matching_job_document j on j.candidate_document_id=d.id where j.matching_job_id=?", String.class, jobId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from matching_job_document j join candidate_document d on d.id=j.candidate_document_id where j.matching_job_id=? and d.storage_key is not null", Long.class, jobId));
    }

    @Test
    void attachmentAndAntivirusFailuresFinishWithSafeInfrastructureCode() {
        jobId = ingestionJob("message-attachment-failure");
        var attachmentFailure = worker(new Attachments(List.of()) {
            @Override public List<Attachment> listAttachments(String messageId, Runnable beforeRequest) { throw new AttachmentException(AttachmentException.Kind.TEMPORARY_FAILURE); }
        }, AntivirusPort.Result.CLEAN);
        ingest(attachmentFailure);
        assertEquals("FAILED", status());
        assertEquals("DOCUMENT_INGESTION_UNAVAILABLE", jobs.get(jobId).failureCode());

        jdbc.update("update matching_job set status='INGESTING_DOCUMENTS',failure_code=null,finished_at=null,claimed_by=null,lease_until=null where id=?", jobId);
        var antivirusFailure = worker(singleAttachment("attachment-av-failure", PDF), AntivirusPort.Result.UNAVAILABLE);
        ingest(antivirusFailure);
        assertEquals("FAILED", status());
        assertEquals("DOCUMENT_INGESTION_UNAVAILABLE", jobs.get(jobId).failureCode());
        assertEquals(0L, jdbc.queryForObject("select count(*) from matching_job_document where matching_job_id=?", Long.class, jobId));
    }

    @Test
    void cancellationAfterTheFirstDownloadDoesNotPersistOrProcessAnotherDocument() {
        jobId = ingestionJob("message-cancel");
        var downloads = new AtomicInteger();
        var attachments = new Attachments(List.of(new OutlookAttachmentPort.Attachment("attachment-one", PDF.length, false, OutlookAttachmentPort.Attachment.Kind.FILE), new OutlookAttachmentPort.Attachment("attachment-two", PDF.length, false, OutlookAttachmentPort.Attachment.Kind.FILE))) {
            @Override public byte[] download(String messageId, String attachmentId, Runnable beforeRequest) {
                beforeRequest.run();
                if (downloads.incrementAndGet() == 1) jobs.cancel(actorId, jobId);
                return PDF;
            }
        };
        var worker = worker(attachments, AntivirusPort.Result.CLEAN);

        ingest(worker);

        assertEquals("CANCELLED", status());
        assertEquals(1, downloads.get());
        assertEquals(0L, jdbc.queryForObject("select count(*) from matching_job_document where matching_job_id=?", Long.class, jobId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from candidate_document", Long.class));
    }

    @Test
    void expiredLeaseReplayDoesNotDownloadOrDuplicateAnAcceptedDocument() {
        jobId = ingestionJob("message-replay");
        var downloads = new AtomicInteger();
        var attachments = singleAttachment("attachment-replay", PDF, downloads);
        var worker = worker(attachments, AntivirusPort.Result.CLEAN);
        ingest(worker);
        jdbc.update("update matching_job set status='INGESTING_DOCUMENTS',claimed_by='expired',lease_until=?,finished_at=null where id=?", Timestamp.from(Instant.now().minusSeconds(1)), jobId);

        ingest(worker);

        assertEquals("ANALYZING", status());
        assertEquals(1, downloads.get());
        assertEquals(1L, jdbc.queryForObject("select count(*) from matching_job_document where matching_job_id=?", Long.class, jobId));
        assertEquals(1, jobs.get(jobId).acceptedDocumentCount());
    }

    private UUID ingestionJob(String messageId) {
        actorId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,force_password_change,created_at,updated_at) values(?,?,?,?,?,?, 'ACTIVE',?,false,?,?)", actorId, "Document test", actorId + "@example.test", actorId + "@example.test", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("ClaveSegura1"), "RECRUITER", now, now, now);
        vacancyId = vacancies.create(actorId, new VacancyService.VacancyCommand("Document test", "Synthetic test vacancy", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"), 0, List.of(new VacancyService.RequirementCommand("Java", 1, true)))).id();
        jobId = jobs.enqueue(actorId, vacancyId).jobId();
        jdbc.update("update matching_job set status='INGESTING_DOCUMENTS' where id=?", jobId);
        jdbc.update("insert into matching_job_discovered_message(id,matching_job_id,graph_message_id,received_at,has_attachments,created_at) values(?,?,?,?,true,?)", UUID.randomUUID(), jobId, messageId, now, now);
        return jobId;
    }

    private void ingest(DocumentIngestionWorker worker) { worker.ingest(jobs.claimNextIngestion("document-test-worker", Duration.ofMinutes(1)).orElseThrow()); }
    private String status() { return jdbc.queryForObject("select status from matching_job where id=?", String.class, jobId); }
    private DocumentIngestionWorker worker(OutlookAttachmentPort attachments, AntivirusPort.Result result) {
        var properties = new DocumentIngestionProperties(true, Duration.ofSeconds(1), Duration.ofMinutes(1), 1024, 20, 20480, 20, storageRoot.toString(), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, "test", "localhost", 3310, Duration.ofSeconds(1));
        var storage = new EncryptedDocumentStorage(properties); storage.validate();
        return new DocumentIngestionWorker(jobs, attachments, documents, storage, content -> result, properties, metrics);
    }
    private static Attachments singleAttachment(String id, byte[] content) { return singleAttachment(id, content, new AtomicInteger()); }
    private static byte[] validPdf() {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
    private static Attachments singleAttachment(String id, byte[] content, AtomicInteger downloads) { return new Attachments(List.of(new OutlookAttachmentPort.Attachment(id, content.length, false, OutlookAttachmentPort.Attachment.Kind.FILE))) { @Override public byte[] download(String messageId, String attachmentId, Runnable beforeRequest) { beforeRequest.run(); downloads.incrementAndGet(); return content; } }; }
    private static class Attachments implements OutlookAttachmentPort {
        private final List<Attachment> attachments;
        private Attachments(List<Attachment> attachments) { this.attachments = attachments; }
        @Override public List<Attachment> listAttachments(String messageId, Runnable beforeRequest) { beforeRequest.run(); return attachments; }
        @Override public byte[] download(String messageId, String attachmentId, Runnable beforeRequest) { throw new UnsupportedOperationException(); }
    }
}
