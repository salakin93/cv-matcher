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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService implements MatchingJobWorkerPort {
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
        return new JobDetail(job.id(), job.vacancyId(), job.vacancyVersion(), job.vacancyTitle(), job.receivedFromUtc(), job.receivedToUtcExclusive(), job.status(), job.attempt(), job.failureCode(), job.createdAt(), job.startedAt(), job.finishedAt(), job.updatedAt(), requirements);
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
        var now = Instant.now();
        var candidate = jdbc.query("select id,status from matching_job where status='QUEUED' or (status in ('DISCOVERING','INGESTING_DOCUMENTS','ANALYZING') and lease_until < ?) order by created_at asc for update skip locked limit 1",
                rs -> rs.next() ? new ClaimCandidate(UUID.fromString(rs.getString("id")), Status.valueOf(rs.getString("status"))) : null, timestamp(now));
        if (candidate == null) return Optional.empty();
        var leaseUntil = now.plus(leaseDuration);
        jdbc.update("update matching_job set status='DISCOVERING',claimed_by=?,lease_until=?,started_at=coalesce(started_at,?),updated_at=? where id=?", workerId, timestamp(leaseUntil), timestamp(now), timestamp(now), candidate.jobId());
        event(candidate.jobId(), null, "CLAIMED", candidate.status(), Status.DISCOVERING);
        return Optional.of(new ClaimedJob(candidate.jobId(), leaseUntil));
    }

    @Override
    @Transactional
    public boolean renewLease(UUID jobId, String workerId, Duration leaseDuration) {
        return jdbc.update("update matching_job set lease_until=?,updated_at=? where id=? and claimed_by=? and lease_until>=? and status in ('DISCOVERING','INGESTING_DOCUMENTS','ANALYZING')", timestamp(Instant.now().plus(leaseDuration)), timestamp(Instant.now()), jobId, workerId, timestamp(Instant.now())) == 1;
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

    @Override
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

    private JobRow locked(UUID jobId) {
        var result = jdbc.query("select id,vacancy_id,vacancy_version,vacancy_title,received_from_utc,received_to_utc_exclusive,status,attempt,failure_code,created_at,started_at,finished_at,updated_at from matching_job where id=? for update", rs -> rs.next() ? row(rs) : null, jobId);
        if (result == null) throw new JobException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND");
        return result;
    }
    private JobRow find(UUID jobId) {
        var result = jdbc.query("select id,vacancy_id,vacancy_version,vacancy_title,received_from_utc,received_to_utc_exclusive,status,attempt,failure_code,created_at,started_at,finished_at,updated_at from matching_job where id=?", rs -> rs.next() ? row(rs) : null, jobId);
        if (result == null) throw new JobException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND");
        return result;
    }
    private static JobRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("vacancy_id")), rs.getLong("vacancy_version"), rs.getString("vacancy_title"), rs.getTimestamp("received_from_utc").toInstant(), rs.getTimestamp("received_to_utc_exclusive").toInstant(), Status.valueOf(rs.getString("status")), rs.getInt("attempt"), rs.getString("failure_code"), rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getTimestamp("updated_at").toInstant());
    }
    private static JobSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException { return new JobSummary(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("vacancy_id")), rs.getLong("vacancy_version"), rs.getString("vacancy_title"), Status.valueOf(rs.getString("status")), rs.getInt("attempt"), rs.getString("failure_code"), rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getTimestamp("updated_at").toInstant()); }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException { var value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private void event(UUID jobId, UUID actorId, String action, Status from, Status to) { jdbc.update("insert into matching_job_event(id,matching_job_id,actor_user_id,action,from_status,to_status,correlation_id,created_at) values(?,?,?,?,?,?,?,?)", UUID.randomUUID(), jobId, actorId, action, from == null ? null : from.name(), to.name(), correlationId(), timestamp(Instant.now())); }
    private void audit(UUID actorId, String action, UUID jobId) { jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,'MATCHING_JOB',?,?,?)", UUID.randomUUID(), actorId, action, jobId, correlationId(), timestamp(Instant.now())); }
    private void mutation(String action, String outcome) { metrics.counter("matching_jobs.mutations", "action", action, "outcome", outcome).increment(); }
    private void queueRequest(String result) { metrics.counter("matching_jobs.queue_requests", "result", result).increment(); }
    private double activeJobCount(Status status) { return jdbc.queryForObject("select count(*) from matching_job where status=?", Long.class, status.name()); }
    private UUID correlationId() { var attributes = RequestContextHolder.getRequestAttributes(); if (attributes instanceof ServletRequestAttributes request) { var value = request.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE); if (value instanceof UUID id) return id; } return null; }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    public enum Status { QUEUED, DISCOVERING, INGESTING_DOCUMENTS, ANALYZING, COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, REAUTHORIZATION_REQUIRED, CANCELLED; public boolean active() { return this == QUEUED || this == DISCOVERING || this == INGESTING_DOCUMENTS || this == ANALYZING; } public boolean terminal() { return !active(); } public boolean canTransitionTo(Status next) { return (this == DISCOVERING && next == INGESTING_DOCUMENTS) || (this == INGESTING_DOCUMENTS && next == ANALYZING); } }
    public record JobAccepted(UUID jobId, Status status, int attempt, String statusUrl) {}
    public record RequirementSnapshot(String description, int weight, boolean mandatory, int position) {}
    public record JobSummary(UUID id, UUID vacancyId, long vacancyVersion, String vacancyTitle, Status status, int attempt, String failureCode, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) {}
    public record JobDetail(UUID id, UUID vacancyId, long vacancyVersion, String vacancyTitle, Instant receivedFromUtc, Instant receivedToUtcExclusive, Status status, int attempt, String failureCode, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt, List<RequirementSnapshot> requirements) {}
    public record JobPage(List<JobSummary> items, int page, int size, long totalItems, long totalPages) {}
    public record ClaimedJob(UUID jobId, Instant leaseUntil) {}
    private record JobRow(UUID id, UUID vacancyId, long vacancyVersion, String vacancyTitle, Instant receivedFromUtc, Instant receivedToUtcExclusive, Status status, int attempt, String failureCode, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) {}
    private record ClaimCandidate(UUID jobId, Status status) {}
}
