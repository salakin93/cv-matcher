package com.cvmatcher.cv_matcher_backend.vacancy.application;

import com.cvmatcher.cv_matcher_backend.audit.application.AuditAction;
import com.cvmatcher.cv_matcher_backend.audit.application.AuditEventPort;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class VacancyService {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/La_Paz");
    private static final Logger log = LoggerFactory.getLogger(VacancyService.class);
    private final JdbcTemplate jdbc;
    private final AuditEventPort audit;
    private final MeterRegistry metrics;

    public VacancyService(JdbcTemplate jdbc, AuditEventPort audit, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Transactional
    public Vacancy create(UUID actorUserId, VacancyCommand command) {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var range = toUtcRange(command.receptionStart(), command.receptionEnd());
        jdbc.update(
                "insert into vacancy(id,title,title_normalized,description,reception_start_utc,reception_end_utc,status,version,created_at,updated_at) values(?,?,?,?,?,?, 'ACTIVE',0,?,?)",
                id, command.title().trim(), normalizeTitle(command.title()), command.description().trim(), Timestamp.from(range.start()), Timestamp.from(range.end()), Timestamp.from(now), Timestamp.from(now)
        );
        replaceRequirements(id, command.requirements());
        audit.record(actorUserId, AuditAction.VACANCY_CREATED, id);
        observed(AuditAction.VACANCY_CREATED, id, "ACTIVE");
        return find(id);
    }

    @Transactional(readOnly = true)
    public List<Vacancy> list() {
        return jdbc.query("select id,title,description,reception_start_utc,reception_end_utc,status,version,created_at,updated_at from vacancy order by updated_at desc", (rs, rowNum) -> vacancy(rs));
    }

    @Transactional(readOnly = true)
    public Vacancy get(UUID id) {
        return find(id);
    }

    @Transactional
    public Vacancy replace(UUID actorUserId, UUID id, long version, VacancyCommand command) {
        var now = Instant.now();
        var range = toUtcRange(command.receptionStart(), command.receptionEnd());
        var updated = jdbc.update(
                "update vacancy set title=?,title_normalized=?,description=?,reception_start_utc=?,reception_end_utc=?,version=version+1,updated_at=? where id=? and version=? and status='ACTIVE'",
                command.title().trim(), normalizeTitle(command.title()), command.description().trim(), Timestamp.from(range.start()), Timestamp.from(range.end()), Timestamp.from(now), id, version
        );
        if (updated == 0) throw mutationFailure(id);
        jdbc.update("delete from vacancy_requirement where vacancy_id=?", id);
        replaceRequirements(id, command.requirements());
        audit.record(actorUserId, AuditAction.VACANCY_UPDATED, id);
        observed(AuditAction.VACANCY_UPDATED, id, "ACTIVE");
        return find(id);
    }

    @Transactional
    public Vacancy archive(UUID actorUserId, UUID id, long version) {
        return changeStatus(actorUserId, id, version, "ACTIVE", "ARCHIVED", AuditAction.VACANCY_ARCHIVED);
    }

    @Transactional
    public Vacancy reactivate(UUID actorUserId, UUID id, long version) {
        return changeStatus(actorUserId, id, version, "ARCHIVED", "ACTIVE", AuditAction.VACANCY_REACTIVATED);
    }

    private Vacancy changeStatus(UUID actorUserId, UUID id, long version, String expected, String target, AuditAction action) {
        var current = find(id);
        if (target.equals(current.status())) return current;
        var updated = jdbc.update(
                "update vacancy set status=?,version=version+1,updated_at=? where id=? and version=? and status=?",
                target, Timestamp.from(Instant.now()), id, version, expected
        );
        if (updated == 0) throw new VacancyConflictException();
        audit.record(actorUserId, action, id);
        observed(action, id, target);
        return find(id);
    }

    private Vacancy find(UUID id) {
        var vacancies = jdbc.query(
                "select id,title,description,reception_start_utc,reception_end_utc,status,version,created_at,updated_at from vacancy where id=?",
                (rs, rowNum) -> vacancy(rs), id
        );
        if (vacancies.isEmpty()) throw new VacancyNotFoundException();
        return vacancies.getFirst();
    }

    private Vacancy vacancy(java.sql.ResultSet rs) throws java.sql.SQLException {
        var id = rs.getObject("id", UUID.class);
        var title = rs.getString("title");
        var requirements = jdbc.query(
                "select id,description,weight,mandatory,position from vacancy_requirement where vacancy_id=? order by position",
                (requirement, rowNum) -> new Requirement(
                        requirement.getObject("id", UUID.class),
                        requirement.getString("description"),
                        requirement.getInt("weight"),
                        requirement.getBoolean("mandatory"),
                        requirement.getInt("position")
                ), id
        );
        var warning = "ACTIVE".equals(rs.getString("status")) && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from vacancy where title_normalized=? and status='ACTIVE' and id<>?)",
                Boolean.class, normalizeTitle(title), id
        ));
        return new Vacancy(
                id, title, rs.getString("description"),
                rs.getTimestamp("reception_start_utc").toInstant().atZone(BUSINESS_ZONE).toLocalDate(),
                rs.getTimestamp("reception_end_utc").toInstant().atZone(BUSINESS_ZONE).toLocalDate(),
                rs.getString("status"), rs.getLong("version"), requirements,
                warning ? List.of("DUPLICATE_ACTIVE_TITLE") : List.of(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private void replaceRequirements(UUID vacancyId, List<RequirementCommand> requirements) {
        for (var position = 0; position < requirements.size(); position++) {
            var requirement = requirements.get(position);
            jdbc.update(
                    "insert into vacancy_requirement(id,vacancy_id,description,weight,mandatory,position) values(?,?,?,?,?,?)",
                    UUID.randomUUID(), vacancyId, requirement.description().trim(), requirement.weight(), requirement.mandatory(), position
            );
        }
    }

    private RuntimeException mutationFailure(UUID id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from vacancy where id=?)", Boolean.class, id))) {
            return new VacancyNotFoundException();
        }
        return new VacancyConflictException();
    }

    private UtcRange toUtcRange(LocalDate start, LocalDate end) {
        return new UtcRange(
                start.atStartOfDay(BUSINESS_ZONE).toInstant(),
                end.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().minusNanos(1_000)
        );
    }

    private String normalizeTitle(String title) {
        return title.trim().toLowerCase(Locale.ROOT);
    }

    private void observed(AuditAction action, UUID vacancyId, String status) {
        metrics.counter("vacancy.operations", "action", action.name(), "status", status).increment();
        log.info("vacancy_event action={} vacancyId={} status={} correlationId={}", action, vacancyId, status, correlationId());
    }

    private UUID correlationId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            var value = servletAttributes.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
            if (value instanceof UUID correlationId) return correlationId;
        }
        return null;
    }

    public record VacancyCommand(String title, String description, LocalDate receptionStart, LocalDate receptionEnd, List<RequirementCommand> requirements) {
    }

    public record RequirementCommand(String description, int weight, boolean mandatory) {
    }

    public record Vacancy(UUID id, String title, String description, LocalDate receptionStart, LocalDate receptionEnd,
                          String status, long version, List<Requirement> requirements, List<String> warnings,
                          Instant createdAt, Instant updatedAt) {
    }

    public record Requirement(UUID id, String description, int weight, boolean mandatory, int position) {
    }

    private record UtcRange(Instant start, Instant end) {
    }
}
