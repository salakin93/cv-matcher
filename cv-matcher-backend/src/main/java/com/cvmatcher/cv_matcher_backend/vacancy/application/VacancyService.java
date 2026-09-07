package com.cvmatcher.cv_matcher_backend.vacancy.application;

import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class VacancyService implements VacancySnapshotPort {
    private static final Logger log = LoggerFactory.getLogger(VacancyService.class);
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public VacancyService(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public VacancyDetail create(UUID actorId, VacancyCommand command) {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var range = VacancyDateRange.from(command.dateFrom(), command.dateTo());
        jdbc.update("insert into vacancy(id,title,description,received_from_utc,received_to_utc_exclusive,status,version,created_at,updated_at) values(?,?,?,?,?,'ACTIVE',0,?,?)",
                id, command.title(), command.description(), timestamp(range.fromUtc()), timestamp(range.toUtcExclusive()), timestamp(now), timestamp(now));
        replaceRequirements(id, command.requirements());
        audit(actorId, "VACANCY_CREATED", id);
        mutation("create", "success");
        return detail(id);
    }

    @Transactional(readOnly = true)
    public VacancyPage list(Status status, int page, int size) {
        var desiredStatus = status == null ? Status.ACTIVE : status;
        var total = jdbc.queryForObject("select count(*) from vacancy where status=?", Long.class, desiredStatus.name());
        var offset = (long) page * size;
        var items = jdbc.query("select id,title,status,version,updated_at from vacancy where status=? order by updated_at desc,id asc limit ? offset ?",
                (rs, row) -> new VacancySummary(
                        UUID.fromString(rs.getString("id")), rs.getString("title"), Status.valueOf(rs.getString("status")),
                        rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()), desiredStatus.name(), size, offset);
        var count = total == null ? 0 : total;
        metrics.counter("vacancy.list_requests", "status_filter", desiredStatus.name().toLowerCase()).increment();
        return new VacancyPage(items, page, size, count, count / size + (count % size == 0 ? 0 : 1));
    }

    @Transactional(readOnly = true)
    public VacancyDetail get(UUID vacancyId) {
        return detail(vacancyId);
    }

    @Override
    @Transactional
    public VacancySnapshot snapshotActive(UUID vacancyId) {
        var current = locked(vacancyId);
        requireActive(current);
        var row = jdbc.query("select title,received_from_utc,received_to_utc_exclusive from vacancy where id=?", rs -> rs.next()
                ? new Object[]{rs.getString("title"), rs.getTimestamp("received_from_utc").toInstant(), rs.getTimestamp("received_to_utc_exclusive").toInstant()} : null, vacancyId);
        var requirements = jdbc.query("select description,weight,mandatory,position from vacancy_requirement where vacancy_id=? order by position asc",
                (rs, ignored) -> new VacancySnapshotPort.RequirementSnapshot(rs.getString("description"), rs.getInt("weight"), rs.getBoolean("mandatory"), rs.getInt("position")), vacancyId);
        return new VacancySnapshotPort.VacancySnapshot(vacancyId, current.version(), (String) row[0], (Instant) row[1], (Instant) row[2], requirements);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID vacancyId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from vacancy where id=?)", Boolean.class, vacancyId));
    }

    @Transactional
    public VacancyDetail update(UUID actorId, UUID vacancyId, VacancyCommand command) {
        var current = locked(vacancyId);
        requireActive(current);
        requireVersion(current, command.expectedVersion());
        var now = Instant.now();
        var range = VacancyDateRange.from(command.dateFrom(), command.dateTo());
        jdbc.update("update vacancy set title=?,description=?,received_from_utc=?,received_to_utc_exclusive=?,version=version+1,updated_at=? where id=?",
                command.title(), command.description(), timestamp(range.fromUtc()), timestamp(range.toUtcExclusive()), timestamp(now), vacancyId);
        replaceRequirements(vacancyId, command.requirements());
        audit(actorId, "VACANCY_UPDATED", vacancyId);
        mutation("update", "success");
        return detail(vacancyId);
    }

    @Transactional
    public void archive(UUID actorId, UUID vacancyId, long expectedVersion) {
        transition(actorId, vacancyId, expectedVersion, Status.ACTIVE, Status.ARCHIVED, "VACANCY_ARCHIVED", "archive");
    }

    @Transactional
    public void reactivate(UUID actorId, UUID vacancyId, long expectedVersion) {
        transition(actorId, vacancyId, expectedVersion, Status.ARCHIVED, Status.ACTIVE, "VACANCY_REACTIVATED", "reactivate");
    }

    private void transition(UUID actorId, UUID vacancyId, long expectedVersion, Status from, Status to, String action, String metricAction) {
        var current = locked(vacancyId);
        requireVersion(current, expectedVersion);
        if (current.status() == to) return;
        if (current.status() != from) throw conflict("VACANCY_ARCHIVED");
        jdbc.update("update vacancy set status=?,version=version+1,updated_at=? where id=?", to.name(), timestamp(Instant.now()), vacancyId);
        audit(actorId, action, vacancyId);
        mutation(metricAction, "success");
    }

    private VacancyState locked(UUID vacancyId) {
        var result = jdbc.query("select id,status,version from vacancy where id=? for update", rs -> rs.next()
                ? new VacancyState(UUID.fromString(rs.getString("id")), Status.valueOf(rs.getString("status")), rs.getLong("version")) : null, vacancyId);
        if (result == null) throw new VacancyException(HttpStatus.NOT_FOUND, "VACANCY_NOT_FOUND");
        return result;
    }

    private void requireActive(VacancyState vacancy) {
        if (vacancy.status() == Status.ARCHIVED) throw conflict("VACANCY_ARCHIVED");
    }

    private void requireVersion(VacancyState vacancy, long expectedVersion) {
        if (vacancy.version() != expectedVersion) throw conflict("VERSION_CONFLICT");
    }

    private VacancyException conflict(String code) {
        mutation("update", "conflict");
        return new VacancyException(HttpStatus.CONFLICT, code);
    }

    private void replaceRequirements(UUID vacancyId, List<RequirementCommand> requirements) {
        jdbc.update("delete from vacancy_requirement where vacancy_id=?", vacancyId);
        for (var position = 0; position < requirements.size(); position++) {
            var requirement = requirements.get(position);
            jdbc.update("insert into vacancy_requirement(id,vacancy_id,description,weight,mandatory,position) values(?,?,?,?,?,?)",
                    UUID.randomUUID(), vacancyId, requirement.description(), requirement.weight(), requirement.mandatory(), position);
        }
    }

    private VacancyDetail detail(UUID vacancyId) {
        var vacancy = jdbc.query("select id,title,description,received_from_utc,received_to_utc_exclusive,status,version,created_at,updated_at from vacancy where id=?",
                rs -> rs.next() ? new VacancyRow(
                        UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getString("description"),
                        rs.getTimestamp("received_from_utc").toInstant(), rs.getTimestamp("received_to_utc_exclusive").toInstant(),
                        Status.valueOf(rs.getString("status")), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()) : null,
                vacancyId);
        if (vacancy == null) throw new VacancyException(HttpStatus.NOT_FOUND, "VACANCY_NOT_FOUND");
        var requirements = jdbc.query("select id,description,weight,mandatory,position from vacancy_requirement where vacancy_id=? order by position asc",
                (rs, row) -> new Requirement(UUID.fromString(rs.getString("id")), rs.getString("description"), rs.getInt("weight"), rs.getBoolean("mandatory"), rs.getInt("position")), vacancyId);
        var range = new VacancyDateRange(vacancy.from(), vacancy.toExclusive());
        return new VacancyDetail(vacancy.id(), vacancy.title(), vacancy.description(), range.dateFrom(), range.dateTo(),
                vacancy.status(), vacancy.version(), requirements, vacancy.createdAt(), vacancy.updatedAt());
    }

    private void audit(UUID actorId, String action, UUID vacancyId) {
        var correlationId = correlationId();
        jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,'VACANCY',?,?,?)",
                UUID.randomUUID(), actorId, action, vacancyId, correlationId, timestamp(Instant.now()));
        log.info("vacancy_event action={} vacancyId={} correlationId={}", action, vacancyId, correlationId);
    }

    private UUID correlationId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes requestAttributes) {
            var value = requestAttributes.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
            if (value instanceof UUID correlationId) return correlationId;
        }
        return null;
    }

    private void mutation(String action, String outcome) {
        metrics.counter("vacancy.mutations", "action", action, "outcome", outcome).increment();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    public enum Status {ACTIVE, ARCHIVED}

    public record RequirementCommand(String description, int weight, boolean mandatory) {}
    public record VacancyCommand(String title, String description, LocalDate dateFrom, LocalDate dateTo, long expectedVersion,
                                 List<RequirementCommand> requirements) {}
    public record Requirement(UUID id, String description, int weight, boolean mandatory, int position) {}
    public record VacancySummary(UUID id, String title, Status status, long version, Instant updatedAt) {}
    public record VacancyPage(List<VacancySummary> items, int page, int size, long totalItems, long totalPages) {}
    public record VacancyDetail(UUID id, String title, String description, LocalDate dateFrom, LocalDate dateTo, Status status,
                                long version, List<Requirement> requirements, Instant createdAt, Instant updatedAt) {}

    private record VacancyState(UUID id, Status status, long version) {}
    private record VacancyRow(UUID id, String title, String description, Instant from, Instant toExclusive, Status status,
                              long version, Instant createdAt, Instant updatedAt) {}
}
