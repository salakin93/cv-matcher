package com.cvmatcher.cv_matcher_backend.administration.application;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AccountAdministrationService {
    private static final Logger log = LoggerFactory.getLogger(AccountAdministrationService.class);
    private static final long ADMINISTRATION_LOCK = 20_260_904_002L;

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public AccountAdministrationService(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public AccountPage list(Role role, Status status, int page, int size) {
        var clauses = new ArrayList<String>();
        var arguments = new ArrayList<Object>();
        if (role != null) {
            clauses.add("role=?");
            arguments.add(role.name());
        }
        if (status != null) {
            clauses.add("status=?");
            arguments.add(status.name());
        }
        var where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
        var total = jdbc.queryForObject("select count(*) from user_account" + where, Long.class, arguments.toArray());
        var offset = (long) page * size;
        arguments.add(size);
        arguments.add(offset);
        var items = jdbc.query(
                "select id,full_name,email,role,status,email_verified_at,force_password_change,updated_at from user_account" + where + " order by full_name asc,id asc limit ? offset ?",
                (rs, rowNum) -> new AccountSummary(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        Role.valueOf(rs.getString("role")),
                        Status.valueOf(rs.getString("status")),
                        instant(rs.getTimestamp("email_verified_at")),
                        rs.getBoolean("force_password_change"),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                arguments.toArray()
        );
        var itemCount = total == null ? 0 : total;
        var totalPages = itemCount / size + (itemCount % size == 0 ? 0 : 1);
        return new AccountPage(items, page, size, itemCount, totalPages);
    }

    @Transactional
    public void changeRole(UUID actorId, UUID targetId, Role desiredRole) {
        lockAdministration();
        var target = target(targetId);
        rejectSelf(actorId, targetId);
        requireVerified(target);
        if (target.role() == desiredRole) return;
        if (target.role() == Role.ADMIN && target.status() == Status.ACTIVE && desiredRole != Role.ADMIN)
            requireAnotherActiveAdmin();
        jdbc.update("update user_account set role=?,updated_at=? where id=?", desiredRole.name(), Timestamp.from(Instant.now()), targetId);
        revokeSessions(targetId);
        audit(actorId, "USER_ROLE_CHANGED", targetId);
        metrics.counter("identity.admin.role_changes", "outcome", "success").increment();
    }

    @Transactional
    public void changeStatus(UUID actorId, UUID targetId, Status desiredStatus) {
        lockAdministration();
        var target = target(targetId);
        rejectSelf(actorId, targetId);
        requireVerified(target);
        if (target.status() == desiredStatus) return;
        if (target.status() == Status.PENDING_VERIFICATION || desiredStatus == Status.PENDING_VERIFICATION)
            throw conflict("EMAIL_NOT_VERIFIED");
        if (target.status() == Status.ACTIVE && desiredStatus == Status.DISABLED && target.role() == Role.ADMIN)
            requireAnotherActiveAdmin();
        jdbc.update("update user_account set status=?,updated_at=? where id=?", desiredStatus.name(), Timestamp.from(Instant.now()), targetId);
        revokeSessions(targetId);
        audit(actorId, desiredStatus == Status.DISABLED ? "USER_DISABLED" : "USER_ENABLED", targetId);
        metrics.counter("identity.admin.status_changes", "outcome", "success").increment();
    }

    private void lockAdministration() {
        jdbc.execute("select pg_advisory_xact_lock(" + ADMINISTRATION_LOCK + ")");
    }

    private AccountState target(UUID targetId) {
        var target = jdbc.query(
                "select id,role,status,email_verified_at from user_account where id=? for update",
                rs -> rs.next() ? new AccountState(
                        UUID.fromString(rs.getString("id")),
                        Role.valueOf(rs.getString("role")),
                        Status.valueOf(rs.getString("status")),
                        instant(rs.getTimestamp("email_verified_at"))
                ) : null,
                targetId
        );
        if (target == null) throw notFound();
        return target;
    }

    private AdministrationException notFound() {
        return new AdministrationException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    private void rejectSelf(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) throw conflict("SELF_ADMINISTRATION_FORBIDDEN");
    }

    private void requireVerified(AccountState target) {
        if (target.emailVerifiedAt() == null) throw conflict("EMAIL_NOT_VERIFIED");
    }

    private void requireAnotherActiveAdmin() {
        var activeAdmins = jdbc.queryForObject("select count(*) from user_account where role='ADMIN' and status='ACTIVE'", Long.class);
        if (activeAdmins != null && activeAdmins <= 1) throw conflict("LAST_ACTIVE_ADMIN");
    }

    private AdministrationException conflict(String code) {
        metrics.counter("identity.admin.conflicts", "code", code).increment();
        return new AdministrationException(HttpStatus.CONFLICT, code);
    }

    private void revokeSessions(UUID userId) {
        jdbc.update("update user_session set revoked_at=? where user_id=? and revoked_at is null", Timestamp.from(Instant.now()), userId);
    }

    private void audit(UUID actorId, String action, UUID targetId) {
        jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,'USER_ACCOUNT',?,?,?)", UUID.randomUUID(), actorId, action, targetId, correlationId(), Timestamp.from(Instant.now()));
        log.info("identity_admin_event action={} actorUserId={} targetUserId={} correlationId={}", action, actorId, targetId, correlationId());
    }

    private UUID correlationId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes requestAttributes) {
            var value = requestAttributes.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
            if (value instanceof UUID correlationId) return correlationId;
        }
        return null;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public enum Role {RECRUITER, ADMIN}

    public enum Status {PENDING_VERIFICATION, ACTIVE, DISABLED}

    public record AccountSummary(UUID id, String fullName, String email, Role role, Status status,
                                 Instant emailVerifiedAt, boolean forcePasswordChange, Instant updatedAt) {
    }

    public record AccountPage(List<AccountSummary> items, int page, int size, long totalItems, long totalPages) {
    }

    private record AccountState(UUID id, Role role, Status status, Instant emailVerifiedAt) {
    }
}
