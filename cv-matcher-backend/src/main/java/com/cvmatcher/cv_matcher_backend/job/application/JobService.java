package com.cvmatcher.cv_matcher_backend.job.application;

import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancySnapshotPort;
import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService implements MatchingJobWorkerPort, DocumentIngestionJobPort {
    private static final java.util.regex.Pattern SAFE_FAILURE_CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private final JdbcTemplate jdbc;
    private final VacancySnapshotPort vacancies;
    private final MeterRegistry metrics;

    public JobService(JdbcTemplate jdbc, VacancySnapshotPort vacancies, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.vacancies = vacancies;
        this.metrics = metrics;
    }

    @PostConstruct
    void registerActiveJobGauges() {
        for (var status : Status.values()) {
            if (status.active()) {
                Gauge.builder("matching_jobs.active", this, service -> service.activeJobCount(status))
                        .tag("status", status.name().toLowerCase())
                        .register(metrics);
            }
        }
    }

    @Transactional
    public JobAccepted enqueue(UUID actorId, UUID vacancyId) {
        VacancySnapshotPort.VacancySnapshot snapshot;
        try {
            snapshot = vacancies.snapshotActive(vacancyId);
        } catch (VacancyException exception) {
            if (exception.status() == HttpStatus.CONFLICT) mutation("enqueue", "conflict");
            throw exception;
        }
        if (snapshot.requirements().isEmpty() || snapshot.requirements().size() > 30) throw new JobException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        var now = Instant.now();
        var jobId = UUID.randomUUID();
        try {
            jdbc.update("insert into matching_job(id,vacancy_id,requested_by_user_id,vacancy_version,vacancy_title,received_from_utc,received_to_utc_exclusive,status,attempt,created_at,updated_at) values(?,?,?,?,?,?,?,'QUEUED',1,?,?)",
                    jobId, vacancyId, actorId, snapshot.version(), snapshot.title(), timestamp(snapshot.receivedFromUtc()), timestamp(snapshot.receivedToUtcExclusive()), timestamp(now), timestamp(now));
        } catch (DuplicateKeyException exception) {
            mutation("enqueue", "conflict");
            queueRequest("active_conflict");
            throw new JobException(HttpStatus.CONFLICT, "ACTIVE_JOB_EXISTS");
        }
        for (var requirement : snapshot.requirements()) {
            jdbc.update("insert into matching_job_requirement(id,matching_job_id,description,weight,mandatory,position) values(?,?,?,?,?,?)", UUID.randomUUID(), jobId,
                    requirement.description(), requirement.weight(), requirement.mandatory(), requirement.position());
        }
        event(jobId, actorId, "QUEUED", null, Status.QUEUED);
        audit(actorId, "REPORT_JOB_QUEUED", jobId);
        mutation("enqueue", "success");
        queueRequest("accepted");
        return new JobAccepted(jobId, Status.QUEUED, 1, "/api/v1/report-jobs/" + jobId);
    }

    @Transactional(readOnly = true)
    public JobPage list(UUID vacancyId, Status status, int page, int size) {
        if (!vacancies.exists(vacancyId)) throw new JobException(HttpStatus.NOT_FOUND, "VACANCY_NOT_FOUND");
        var where = status == null ? "vacancy_id=?" : "vacancy_id=? and status=?";
        Object[] parameters = status == null ? new Object[]{vacancyId, size, (long) page * size} : new Object[]{vacancyId, status.name(), size, (long) page * size};
        var total = status == null
                ? jdbc.queryForObject("select count(*) from matching_job where vacancy_id=?", Long.class, vacancyId)
                : jdbc.queryForObject("select count(*) from matching_job where vacancy_id=? and status=?", Long.class, vacancyId, status.name());
        var items = jdbc.query("select id,vacancy_id,vacancy_version,vacancy_title,status,attempt,failure_code,created_at,started_at,finished_at,updated_at from matching_job where " + where + " order by created_at desc,id asc limit ? offset ?",
                (rs, ignored) -> summary(rs), parameters);
        var count = total == null ? 0 : total;
        return new JobPage(items, page, size, count, count / size + (count % size == 0 ? 0 : 1));
    }

    @Transactional(readOnly = true)
    public JobDetail get(UUID jobId) {
        var job = find(jobId);
        var requirements = jdbc.query("select description,weight,mandatory,position from matching_job_requirement where matching_job_id=? order by position asc",
                (rs, ignored) -> new RequirementSnapshot(rs.getString("description"), rs.getInt("weight"), rs.getBoolean("mandatory"), rs.getInt("position")), jobId);
        return new JobDetail(job.id(), job.vacancyId(), job.vacancyVersion(), job.vacancyTitle(), job.receivedFromUtc(), job.receivedToUtcExclusive(), job.status(), job.attempt(), job.failureCode(), job.discoveredMessageCount(), job.acceptedDocumentCount(), job.ignoredDocumentCount(), job.quarantinedDocumentCount(), job.discoveryCompletedAt(), job.ingestionCompletedAt(), job.createdAt(), job.startedAt(), job.finishedAt(), job.updatedAt(), requirements);
    }

    @Transactional
    public void cancel(UUID actorId, UUID jobId) {
        var job = locked(jobId);
        if (job.status() == Status.CANCELLED) return;
        if (!job.status().active()) {
            mutation("cancel", "conflict");
            throw new JobException(HttpStatus.CONFLICT, "JOB_NOT_CANCELLABLE");
        }
        var now = Instant.now();
        jdbc.update("update matching_job set status='CANCELLED',finished_at=?,updated_at=?,claimed_by=null,lease_until=null where id=?", timestamp(now), timestamp(now), jobId);
        event(jobId, actorId, "CANCELLED", job.status(), Status.CANCELLED);
        audit(actorId, "REPORT_JOB_CANCELLED", jobId);
        mutation("cancel", "success");
    }

    @Transactional
    public JobAccepted retry(UUID actorId, UUID jobId) {
        var previous = locked(jobId);
        if (previous.status() != Status.FAILED && previous.status() != Status.REAUTHORIZATION_REQUIRED) {
            mutation("retry", "conflict");
            throw new JobException(HttpStatus.CONFLICT, "JOB_NOT_RETRYABLE");
        }
        var now = Instant.now();
        var newId = UUID.randomUUID();
        try {
            jdbc.update("insert into matching_job(id,vacancy_id,requested_by_user_id,vacancy_version,vacancy_title,received_from_utc,received_to_utc_exclusive,status,attempt,retry_of_job_id,created_at,updated_at) values(?,?,?,?,?,?,?,'QUEUED',?,?,?,?)",
                    newId, previous.vacancyId(), actorId, previous.vacancyVersion(), previous.vacancyTitle(), timestamp(previous.receivedFromUtc()), timestamp(previous.receivedToUtcExclusive()), previous.attempt() + 1, jobId, timestamp(now), timestamp(now));
        } catch (DuplicateKeyException exception) {
            mutation("retry", "conflict");
            throw new JobException(HttpStatus.CONFLICT, "ACTIVE_JOB_EXISTS");
        }
        var requirements = jdbc.query("select description,weight,mandatory,position from matching_job_requirement where matching_job_id=? order by position asc",
                (rs, ignored) -> new RequirementSnapshot(rs.getString("description"), rs.getInt("weight"), rs.getBoolean("mandatory"), rs.getInt("position")), jobId);
        for (var requirement : requirements) {
            jdbc.update("insert into matching_job_requirement(id,matching_job_id,description,weight,mandatory,position) values(?,?,?,?,?,?)", UUID.randomUUID(), newId,
                    requirement.description(), requirement.weight(), requirement.mandatory(), requirement.position());
        }
        event(newId, actorId, "QUEUED", null, Status.QUEUED);
        audit(actorId, "REPORT_JOB_RETRIED", newId);
        mutation("retry", "success");
        return new JobAccepted(newId, Status.QUEUED, previous.attempt() + 1, "/api/v1/report-jobs/" + newId);
    }

    @Override
    @Transactional
    public Optional<ClaimedJob> claimNext(String workerId, Duration leaseDuration) {
        return claimNext(workerId, leaseDuration, "status='QUEUED' or (status in ('DISCOVERING','INGESTING_DOCUMENTS','ANALYZING') and lease_until < ?)");
    }

    @Transactional
    public Optional<ClaimedJob> claimNextDiscovery(String workerId, Duration leaseDuration) {
        return claimNext(workerId, leaseDuration, "status='QUEUED' or (status='DISCOVERING' and lease_until < ?)");
    }

    @Override
    @Transactional
    public Optional<DocumentIngestionJobPort.IngestionClaim> claimNextIngestion(String workerId, Duration leaseDuration) {
        var now = Instant.now();
        var candidate = jdbc.query("select id,received_from_utc,received_to_utc_exclusive from matching_job where status='INGESTING_DOCUMENTS' and (lease_until is null or lease_until < ?) order by created_at asc for update skip locked limit 1",
                rs -> rs.next() ? new ClaimCandidate(UUID.fromString(rs.getString("id")), Status.INGESTING_DOCUMENTS, rs.getTimestamp("received_from_utc").toInstant(), rs.getTimestamp("received_to_utc_exclusive").toInstant()) : null, timestamp(now));
        if (candidate == null) return Optional.empty();
        var leaseUntil = now.plus(leaseDuration);
        jdbc.update("update matching_job set claimed_by=?,lease_until=?,started_at=coalesce(started_at,?),updated_at=? where id=?", workerId, timestamp(leaseUntil), timestamp(now), timestamp(now), candidate.jobId());
        event(candidate.jobId(), null, "DOCUMENT_INGESTION_STARTED", Status.INGESTING_DOCUMENTS, Status.INGESTING_DOCUMENTS);
        return Optional.of(new DocumentIngestionJobPort.IngestionClaim(candidate.jobId(), candidate.fromUtc(), candidate.toUtcExclusive(), leaseUntil));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentIngestionJobPort.DiscoveredMessageReference> discoveredMessages(UUID jobId, String workerId) {
        var valid = jdbc.queryForObject("select count(*) from matching_job where id=? and claimed_by=? and lease_until>=? and status='INGESTING_DOCUMENTS'", Integer.class, jobId, workerId, timestamp(Instant.now()));
        if (valid == null || valid != 1) return List.of();
        return jdbc.query("select graph_message_id,received_at,has_attachments from matching_job_discovered_message where matching_job_id=? and has_attachments=true order by received_at asc,id asc",
                (rs, ignored) -> new DocumentIngestionJobPort.DiscoveredMessageReference(rs.getString("graph_message_id"), rs.getTimestamp("received_at").toInstant(), rs.getBoolean("has_attachments")), jobId);
    }

    @Override
    @Transactional
    public boolean persistDocument(UUID jobId, String workerId, DocumentIngestionJobPort.DocumentWrite document) {
        var active = jdbc.queryForObject("select count(*) from matching_job where id=? and claimed_by=? and lease_until>=current_timestamp and status='INGESTING_DOCUMENTS' for update", Integer.class, jobId, workerId);
        if (active == null || active != 1) return false;
        var now = timestamp(Instant.now());
        var documentId = jdbc.query("insert into candidate_document(id,graph_message_id_hash,graph_attachment_id_hash,content_sha256,storage_key,format,size_bytes,encryption_key_version,status,ignored_reason_code,received_at,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (graph_message_id_hash,graph_attachment_id_hash) do update set updated_at=excluded.updated_at returning id",
                rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, UUID.randomUUID(), hash(document.graphMessageId()), hash(document.graphAttachmentId()), document.contentHash(), document.storageKey(), document.format(), document.sizeBytes() == 0 ? null : document.sizeBytes(), document.encryptionKeyVersion(), document.disposition().equals("ACCEPTED") ? "AVAILABLE" : document.disposition().equals("QUARANTINED") ? "QUARANTINED" : "IGNORED", document.reasonCode(), timestamp(document.receivedAt()), now, now);
        if (documentId == null) return false;
        var position = jdbc.queryForObject("select coalesce(max(position)+1,0) from matching_job_document where matching_job_id=?", Integer.class, jobId);
        var inserted = jdbc.update("insert into matching_job_document(id,matching_job_id,candidate_document_id,position,disposition,reason_code,created_at) values(?,?,?,?,?,?,?) on conflict (matching_job_id,candidate_document_id) do nothing", UUID.randomUUID(), jobId, documentId, position, document.disposition(), document.reasonCode(), now);
        if (inserted == 1) {
            jdbc.update("update matching_job set accepted_document_count=(select count(*) from matching_job_document where matching_job_id=? and disposition='ACCEPTED'),ignored_document_count=(select count(*) from matching_job_document where matching_job_id=? and disposition='IGNORED'),quarantined_document_count=(select count(*) from matching_job_document where matching_job_id=? and disposition='QUARANTINED'),updated_at=? where id=?", jobId, jobId, jobId, now, jobId);
            event(jobId, null, document.disposition().equals("ACCEPTED") ? "DOCUMENT_ACCEPTED" : document.disposition().equals("QUARANTINED") ? "DOCUMENT_QUARANTINED" : "DOCUMENT_IGNORED", Status.INGESTING_DOCUMENTS, Status.INGESTING_DOCUMENTS);
        }
        return true;
    }

    @Override
    @Transactional
    public boolean completeIngestion(UUID jobId, String workerId, boolean hasAvailableDocuments) {
        var now = Instant.now();
        var current = jdbc.query("select status from matching_job where id=? and claimed_by=? and lease_until>=? for update", rs -> rs.next() ? Status.valueOf(rs.getString(1)) : null, jobId, workerId, timestamp(now));
        if (current != Status.INGESTING_DOCUMENTS) return false;
        if (!hasAvailableDocuments) {
            jdbc.update("update matching_job set status='FAILED',failure_code='NO_VALID_CV_DOCUMENTS',ingestion_completed_at=?,finished_at=?,updated_at=?,claimed_by=null,lease_until=null where id=?", timestamp(now), timestamp(now), timestamp(now), jobId);
            event(jobId, null, "DOCUMENT_INGESTION_FAILED", current, Status.FAILED);
            audit(null, "DOCUMENT_INGESTION_FAILED", jobId);
            return true;
        }
        jdbc.update("update matching_job set status='ANALYZING',ingestion_completed_at=?,updated_at=?,claimed_by=null,lease_until=null where id=?", timestamp(now), timestamp(now), jobId);
        event(jobId, null, "DOCUMENT_INGESTION_COMPLETED", current, Status.ANALYZING);
        audit(null, "DOCUMENT_INGESTION_COMPLETED", jobId);
        return true;
    }

    @Override
    @Transactional
    public boolean finishIngestion(UUID jobId, String workerId, DocumentIngestionJobPort.IngestionTerminalStatus terminalStatus, String failureCode) {
        return finishClaimed(jobId, workerId,
                terminalStatus == DocumentIngestionJobPort.IngestionTerminalStatus.REAUTHORIZATION_REQUIRED ? Status.REAUTHORIZATION_REQUIRED : Status.FAILED,
                failureCode);
    }

    private Optional<ClaimedJob> claimNext(String workerId, Duration leaseDuration, String eligible) {
        var now = Instant.now();
        var candidate = jdbc.query("select id,status,received_from_utc,received_to_utc_exclusive from matching_job where " + eligible + " order by created_at asc for update skip locked limit 1",
                rs -> rs.next() ? new ClaimCandidate(UUID.fromString(rs.getString("id")), Status.valueOf(rs.getString("status")), rs.getTimestamp("received_from_utc").toInstant(), rs.getTimestamp("received_to_utc_exclusive").toInstant()) : null, timestamp(now));
        if (candidate == null) return Optional.empty();
        var leaseUntil = now.plus(leaseDuration);
        jdbc.update("update matching_job set status='DISCOVERING',claimed_by=?,lease_until=?,started_at=coalesce(started_at,?),updated_at=? where id=?", workerId, timestamp(leaseUntil), timestamp(now), timestamp(now), candidate.jobId());
        event(candidate.jobId(), null, "DISCOVERY_STARTED", candidate.status(), Status.DISCOVERING);
        return Optional.of(new ClaimedJob(candidate.jobId(), candidate.fromUtc(), candidate.toUtcExclusive(), leaseUntil));
    }

    @Override
    @Transactional
    public boolean renewLease(UUID jobId, String workerId, Duration leaseDuration) {
        return jdbc.update("update matching_job set lease_until=?,updated_at=? where id=? and claimed_by=? and lease_until>=? and status in ('DISCOVERING','INGESTING_DOCUMENTS','ANALYZING')", timestamp(Instant.now().plus(leaseDuration)), timestamp(Instant.now()), jobId, workerId, timestamp(Instant.now())) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCancelled(UUID jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select status='CANCELLED' from matching_job where id=?", Boolean.class, jobId));
    }

    @Override
    @Transactional
    public boolean transitionClaimed(UUID jobId, String workerId, Status nextStatus) {
        if (nextStatus != Status.INGESTING_DOCUMENTS && nextStatus != Status.ANALYZING) throw new IllegalArgumentException("in-progress status required");
        var now = Instant.now();
        var current = jdbc.query("select status from matching_job where id=? and claimed_by=? and lease_until>=? for update", rs -> rs.next() ? Status.valueOf(rs.getString("status")) : null,
                jobId, workerId, timestamp(now));
        if (current == null) return false;
        if (!current.canTransitionTo(nextStatus)) return false;
        jdbc.update("update matching_job set status=?,updated_at=? where id=?", nextStatus.name(), timestamp(now), jobId);
        event(jobId, null, "STATUS_CHANGED", current, nextStatus);
        return true;
    }

    @Transactional
    public boolean finishClaimed(UUID jobId, String workerId, Status terminalStatus, String failureCode) {
        if (!terminalStatus.terminal()) throw new IllegalArgumentException("terminal status required");
        if (failureCode != null && !SAFE_FAILURE_CODE.matcher(failureCode).matches()) throw new IllegalArgumentException("safe failure code required");
        var now = Instant.now();
        var current = jdbc.query("select status from matching_job where id=? and claimed_by=? and lease_until>=? and status in ('DISCOVERING','INGESTING_DOCUMENTS','ANALYZING') for update",
                rs -> rs.next() ? Status.valueOf(rs.getString("status")) : null, jobId, workerId, timestamp(now));
        if (current == null) return false;
        var updated = jdbc.update("update matching_job set status=?,failure_code=?,finished_at=?,updated_at=?,claimed_by=null,lease_until=null where id=? and claimed_by=? and lease_until>=? and status in ('DISCOVERING','INGESTING_DOCUMENTS','ANALYZING')", terminalStatus.name(), failureCode, timestamp(now), timestamp(now), jobId, workerId, timestamp(now));
        if (updated == 1) event(jobId, null, "TERMINATED", current, terminalStatus);
        return updated == 1;
    }

    @Transactional
    public boolean saveDiscoveredPage(UUID jobId, String workerId, List<DiscoveredMessage> messages) {
        var current = jdbc.query("select status from matching_job where id=? and claimed_by=? and lease_until>=? for update", rs -> rs.next() ? Status.valueOf(rs.getString(1)) : null, jobId, workerId, timestamp(Instant.now()));
        if (current != Status.DISCOVERING) return false;
        for (var message : messages) jdbc.update("insert into matching_job_discovered_message(id,matching_job_id,graph_message_id,received_at,has_attachments,created_at) values(?,?,?,?,?,?) on conflict (matching_job_id,graph_message_id) do nothing", UUID.randomUUID(), jobId, message.graphMessageId(), timestamp(message.receivedAt()), message.hasAttachments(), timestamp(Instant.now()));
        jdbc.update("update matching_job set discovered_message_count=(select count(*) from matching_job_discovered_message where matching_job_id=?),updated_at=? where id=?", jobId, timestamp(Instant.now()), jobId);
        event(jobId, null, "DISCOVERY_PAGE_SAVED", Status.DISCOVERING, Status.DISCOVERING);
        return true;
    }

    @Transactional
    public boolean completeDiscovery(UUID jobId, String workerId, String warningCode) {
        var now = Instant.now();
        var current = jdbc.query("select status from matching_job where id=? and claimed_by=? and lease_until>=? for update", rs -> rs.next() ? Status.valueOf(rs.getString(1)) : null, jobId, workerId, timestamp(now));
        if (current != Status.DISCOVERING) return false;
        jdbc.update("update matching_job set status='INGESTING_DOCUMENTS',failure_code=?,discovered_message_count=(select count(*) from matching_job_discovered_message where matching_job_id=?),discovery_completed_at=?,updated_at=?,claimed_by=null,lease_until=null where id=?", warningCode, jobId, timestamp(now), timestamp(now), jobId);
        event(jobId, null, "DISCOVERY_COMPLETED", Status.DISCOVERING, Status.INGESTING_DOCUMENTS);
        audit(null, "OUTLOOK_DISCOVERY_COMPLETED", jobId);
        return true;
    }

    @Transactional
    public boolean failDiscovery(UUID jobId, String workerId, Status terminalStatus, String failureCode) {
        if (terminalStatus != Status.FAILED && terminalStatus != Status.REAUTHORIZATION_REQUIRED) throw new IllegalArgumentException("discovery terminal status required");
        if (!finishClaimed(jobId, workerId, terminalStatus, failureCode)) return false;
        event(jobId, null, terminalStatus == Status.FAILED ? "DISCOVERY_FAILED" : "DISCOVERY_REAUTH_REQUIRED", Status.DISCOVERING, terminalStatus);
        audit(null, "OUTLOOK_DISCOVERY_FAILED", jobId);
        return true;
    }

    private JobRow locked(UUID jobId) {
        var result = jdbc.query("select id,vacancy_id,vacancy_version,vacancy_title,received_from_utc,received_to_utc_exclusive,status,attempt,failure_code,discovered_message_count,accepted_document_count,ignored_document_count,quarantined_document_count,discovery_completed_at,ingestion_completed_at,created_at,started_at,finished_at,updated_at from matching_job where id=? for update", rs -> rs.next() ? row(rs) : null, jobId);
        if (result == null) throw new JobException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND");
        return result;
    }
    private JobRow find(UUID jobId) {
        var result = jdbc.query("select id,vacancy_id,vacancy_version,vacancy_title,received_from_utc,received_to_utc_exclusive,status,attempt,failure_code,discovered_message_count,accepted_document_count,ignored_document_count,quarantined_document_count,discovery_completed_at,ingestion_completed_at,created_at,started_at,finished_at,updated_at from matching_job where id=?", rs -> rs.next() ? row(rs) : null, jobId);
        if (result == null) throw new JobException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND");
        return result;
    }
    private static JobRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("vacancy_id")), rs.getLong("vacancy_version"), rs.getString("vacancy_title"), rs.getTimestamp("received_from_utc").toInstant(), rs.getTimestamp("received_to_utc_exclusive").toInstant(), Status.valueOf(rs.getString("status")), rs.getInt("attempt"), rs.getString("failure_code"), rs.getInt("discovered_message_count"), rs.getInt("accepted_document_count"), rs.getInt("ignored_document_count"), rs.getInt("quarantined_document_count"), instant(rs, "discovery_completed_at"), instant(rs, "ingestion_completed_at"), rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getTimestamp("updated_at").toInstant());
    }
    private static JobSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException { return new JobSummary(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("vacancy_id")), rs.getLong("vacancy_version"), rs.getString("vacancy_title"), Status.valueOf(rs.getString("status")), rs.getInt("attempt"), rs.getString("failure_code"), rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getTimestamp("updated_at").toInstant()); }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException { var value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private void event(UUID jobId, UUID actorId, String action, Status from, Status to) { jdbc.update("insert into matching_job_event(id,matching_job_id,actor_user_id,action,from_status,to_status,correlation_id,created_at) values(?,?,?,?,?,?,?,?)", UUID.randomUUID(), jobId, actorId, action, from == null ? null : from.name(), to.name(), correlationId(), timestamp(Instant.now())); }
    private void audit(UUID actorId, String action, UUID jobId) { jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,'MATCHING_JOB',?,?,?)", UUID.randomUUID(), actorId, action, jobId, correlationId(), timestamp(Instant.now())); }
    private void mutation(String action, String outcome) { metrics.counter("matching_jobs.mutations", "action", action, "outcome", outcome).increment(); }
    private void queueRequest(String result) { metrics.counter("matching_jobs.queue_requests", "result", result).increment(); }
    private double activeJobCount(Status status) { return jdbc.queryForObject("select count(*) from matching_job where status=?", Long.class, status.name()); }
    private UUID correlationId() { var attributes = RequestContextHolder.getRequestAttributes(); if (attributes instanceof ServletRequestAttributes request) { var value = request.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE); if (value instanceof UUID id) return id; } return UUID.randomUUID(); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public enum Status { QUEUED, DISCOVERING, INGESTING_DOCUMENTS, ANALYZING, COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, REAUTHORIZATION_REQUIRED, CANCELLED; public boolean active() { return this == QUEUED || this == DISCOVERING || this == INGESTING_DOCUMENTS || this == ANALYZING; } public boolean terminal() { return !active(); } public boolean canTransitionTo(Status next) { return (this == DISCOVERING && next == INGESTING_DOCUMENTS) || (this == INGESTING_DOCUMENTS && next == ANALYZING); } }
    public record JobAccepted(UUID jobId, Status status, int attempt, String statusUrl) {}
    public record RequirementSnapshot(String description, int weight, boolean mandatory, int position) {}
    public record JobSummary(UUID id, UUID vacancyId, long vacancyVersion, String vacancyTitle, Status status, int attempt, String failureCode, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) {}
    public record JobDetail(UUID id, UUID vacancyId, long vacancyVersion, String vacancyTitle, Instant receivedFromUtc, Instant receivedToUtcExclusive, Status status, int attempt, String failureCode, int discoveredMessageCount, int acceptedDocumentCount, int ignoredDocumentCount, int quarantinedDocumentCount, Instant discoveryCompletedAt, Instant ingestionCompletedAt, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt, List<RequirementSnapshot> requirements) {}
    public record JobPage(List<JobSummary> items, int page, int size, long totalItems, long totalPages) {}
    public record ClaimedJob(UUID jobId, Instant receivedFromUtc, Instant receivedToUtcExclusive, Instant leaseUntil) {}
    public record DiscoveredMessage(String graphMessageId, Instant receivedAt, boolean hasAttachments) {}
    private record JobRow(UUID id, UUID vacancyId, long vacancyVersion, String vacancyTitle, Instant receivedFromUtc, Instant receivedToUtcExclusive, Status status, int attempt, String failureCode, int discoveredMessageCount, int acceptedDocumentCount, int ignoredDocumentCount, int quarantinedDocumentCount, Instant discoveryCompletedAt, Instant ingestionCompletedAt, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) {}
    private record ClaimCandidate(UUID jobId, Status status, Instant fromUtc, Instant toUtcExclusive) {}
}
