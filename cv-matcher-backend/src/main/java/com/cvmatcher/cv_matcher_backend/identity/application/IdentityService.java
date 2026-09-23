package com.cvmatcher.cv_matcher_backend.identity.application;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class IdentityService {
    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    private final JdbcTemplate jdbc;
    private final SecurityProperties props;
    private final JwtService jwt;
    private final MeterRegistry metrics;
    private final VerificationOutbox verificationOutbox;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Autowired
    public IdentityService(JdbcTemplate jdbc, SecurityProperties props, JwtService jwt, MeterRegistry metrics, VerificationOutbox verificationOutbox) {
        this.jdbc = jdbc;
        this.props = props;
        this.jwt = jwt;
        this.metrics = metrics;
        this.verificationOutbox = verificationOutbox;
    }

    @Transactional
    public void register(String name, String email, String password) {
        validatePassword(password);
        var normalized = normalize(email);
        if (exists(normalized)) return;
        var id = UUID.randomUUID();
        var now = Instant.now();
        var inserted = jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,created_at,updated_at) values(?,?,?,?,?,'RECRUITER','PENDING_VERIFICATION',?,?) on conflict (email_normalized) do nothing", id, name.trim(), email.trim(), normalized, encoder.encode(password), timestamp(now), timestamp(now));
        if (inserted == 0) return;
        sendToken(id, "EMAIL_VERIFICATION", props.verificationHours() * 3600);
        audit(null, "ACCOUNT_REGISTERED", id);
        count("identity.registrations");
    }

    @Transactional
    public void confirm(String raw, String purpose) {
        var row = jdbc.queryForList("select id,user_id,target_email,expires_at,consumed_at,purpose from account_action_token where token_hash=?", hash(raw)).stream().findFirst().orElse(null);
        if (row == null || !purpose.equals(row.get("purpose")) || !((java.sql.Timestamp) row.get("expires_at")).toInstant().isAfter(Instant.now()) || row.get("consumed_at") != null)
            throw new IllegalArgumentException("Invalid or expired token");
        var user = UUID.fromString(row.get("user_id").toString());
        if ("EMAIL_VERIFICATION".equals(purpose)) {
            var now = Instant.now();
            jdbc.update("update user_account set status='ACTIVE',email_verified_at=?,updated_at=? where id=?", timestamp(now), timestamp(now), user);
        }
        if (jdbc.update("update account_action_token set consumed_at=? where id=? and consumed_at is null", timestamp(Instant.now()), UUID.fromString(row.get("id").toString())) != 1)
            throw new IllegalArgumentException("Invalid or expired token");
        audit(user, eventForTokenConfirmation(purpose), user);
        count("identity.token.confirmations", "purpose", purpose);
    }

    @Transactional(noRollbackFor = SecurityException.class)
    public Login login(String email, String password) {
        var normalizedEmail = normalize(email);

        var user = jdbc.queryForList(
                "select * from user_account where email_normalized=? for update",
                normalizedEmail
        ).stream().findFirst().orElse(null);

        if (user == null) {
            count("identity.logins", "outcome", "failure");
            logFailure("INVALID_CREDENTIALS", null);
            throw new SecurityException("Invalid credentials");
        }

        var id = UUID.fromString(user.get("id").toString());
        var locked = (java.sql.Timestamp) user.get("locked_until");
        var status = (String) user.get("status");
        var passwordHash = (String) user.get("password_hash");
        var passwordMatches = encoder.matches(password, passwordHash);

        if (locked != null && locked.toInstant().isAfter(Instant.now())) {
            if (passwordMatches) {
                throw new AccountAccessException(AccountAccessException.Reason.ACCOUNT_TEMPORARILY_LOCKED);
            }
            count("identity.logins", "outcome", "locked");
            logFailure("ACCOUNT_LOCKED", id);
            throw new SecurityException("Invalid credentials");
        }

        if ("PENDING_VERIFICATION".equals(status) && passwordMatches) {
            throw new AccountAccessException(AccountAccessException.Reason.EMAIL_VERIFICATION_REQUIRED);
        }
        if (!"ACTIVE".equals(status) && passwordMatches) {
            count("identity.logins", "outcome", "failure");
            logFailure("INVALID_CREDENTIALS", id);
            throw new SecurityException("Invalid credentials");
        }

        if (!passwordMatches) {
            var attempts = ((Number) user.get("failed_login_attempts")).intValue() + 1;
            var lockedUntil = attempts >= props.loginMaxFailedAttempts()
                    ? Instant.now().plusSeconds(props.loginLockMinutes() * 60)
                    : null;

            jdbc.update(
                    "update user_account set failed_login_attempts=?, locked_until=?, updated_at=? where id=?",
                    attempts,
                    lockedUntil == null ? null : timestamp(lockedUntil),
                    timestamp(Instant.now()),
                    id
            );
            if (attempts == props.loginMaxFailedAttempts()) audit(id, "LOGIN_LOCKED", id);
            if (attempts == props.loginMaxFailedAttempts()) count("identity.login.locks");
            count("identity.logins", "outcome", "failure");
            logFailure("INVALID_CREDENTIALS", id);

            throw new SecurityException("Invalid credentials");
        }

        var sessionId = UUID.randomUUID();
        var refreshToken = random();

        jdbc.update(
                "insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)",
                sessionId,
                id,
                hash(refreshToken),
                timestamp(Instant.now().plusSeconds(props.sessionHours() * 3600)),
                timestamp(Instant.now())
        );

        jdbc.update(
                "update user_account set failed_login_attempts=0, locked_until=null where id=?",
                id
        );

        audit(id, "LOGIN_SUCCEEDED", id);
        count("identity.logins", "outcome", "success");

        var role = (String) user.get("role");
        return new Login(
                jwt.issue(id, role, sessionId),
                refreshToken,
                sessionId,
                new UserInfo(id, (String) user.get("full_name"), (String) user.get("email"), role, status)
        );
    }

    @Transactional(noRollbackFor = SecurityException.class)
    public Login refresh(String raw) {
        var s = jdbc.queryForList("select s.*,u.full_name,u.email,u.role,u.status from user_session s join user_account u on u.id=s.user_id where s.refresh_token_hash=?", hash(raw)).stream().findFirst().orElse(null);
        if (s == null) {
            count("identity.refreshes", "outcome", "failure");
            logFailure("INVALID_SESSION", null);
            throw new SecurityException("Invalid session");
        }
        var user = UUID.fromString(s.get("user_id").toString());
        if (s.get("revoked_at") != null) {
            revokeAll(user);
            audit(user, "REFRESH_TOKEN_REUSE", user);
            count("identity.refreshes", "outcome", "reused");
            logFailure("REFRESH_TOKEN_REUSE", user);
            throw new SecurityException("Invalid session");
        }
        if (!"ACTIVE".equals(s.get("status")) || !((java.sql.Timestamp) s.get("expires_at")).toInstant().isAfter(Instant.now())) {
            count("identity.refreshes", "outcome", "failure");
            logFailure("INVALID_SESSION", user);
            throw new SecurityException("Invalid session");
        }
        var next = random();
        var id = UUID.fromString(s.get("id").toString());
        if (jdbc.update("update user_session set revoked_at=? where id=? and revoked_at is null", timestamp(Instant.now()), id) != 1) {
            revokeAll(user);
            count("identity.refreshes", "outcome", "reused");
            logFailure("REFRESH_TOKEN_REUSE", user);
            throw new SecurityException("Invalid session");
        }
        var nextId = UUID.randomUUID();
        jdbc.update("insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)", nextId, user, hash(next), s.get("expires_at"), timestamp(Instant.now()));
        audit(user, "REFRESH_ROTATED", user);
        count("identity.refreshes", "outcome", "success");
        var role = (String) s.get("role");
        return new Login(
                jwt.issue(user, role, nextId),
                next,
                nextId,
                new UserInfo(user, (String) s.get("full_name"), (String) s.get("email"), role, (String) s.get("status"))
        );
    }

    @Transactional
    public void requestToken(String email, String purpose) {
        var account = jdbc.query(
                "select id,status from user_account where email_normalized=? for update",
                rs -> rs.next() ? new Object[]{UUID.fromString(rs.getString("id")), rs.getString("status")} : null,
                normalize(email)
        );
        if (account == null) return;

        var userId = (UUID) account[0];
        var status = (String) account[1];

        if ("EMAIL_VERIFICATION".equals(purpose) && "PENDING_VERIFICATION".equals(status) && canResendVerification(userId)) {
            sendToken(userId, purpose, props.verificationHours() * 3600);
            count("identity.verification.resends");
        }
    }

    @Transactional
    public void logout(String raw) {
        var session = jdbc.query(
                "select id,user_id from user_session where refresh_token_hash=?",
                rs -> rs.next() ? new UUID[]{UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("user_id"))} : null,
                hash(raw)
        );
        if (session != null && jdbc.update("update user_session set revoked_at=? where id=? and revoked_at is null", timestamp(Instant.now()), session[0]) == 1) {
            audit(session[1], "LOGOUT", session[1]);
            count("identity.logouts");
        }
    }

    public UserInfo me(UUID userId) {
        var row = jdbc.queryForMap("select id,full_name,email,role,status from user_account where id=?", userId);
        return new UserInfo(UUID.fromString(row.get("id").toString()), (String) row.get("full_name"), (String) row.get("email"), (String) row.get("role"), (String) row.get("status"));
    }

    public boolean exists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from user_account where email_normalized=?)", Boolean.class, email));
    }

    private void sendToken(UUID id, String purpose, long seconds) {
        jdbc.update("update account_action_token set consumed_at=? where user_id=? and purpose=? and consumed_at is null", timestamp(Instant.now()), id, purpose);
        var raw = random();
        jdbc.update("insert into account_action_token(id,user_id,token_hash,purpose,target_email,expires_at,created_at) values(?,?,?,?,?,?,?)", UUID.randomUUID(), id, hash(raw), purpose, null, timestamp(Instant.now().plusSeconds(seconds)), timestamp(Instant.now()));
        var email = jdbc.queryForObject("select email from user_account where id=?", String.class, id);
        if ("EMAIL_VERIFICATION".equals(purpose)) {
            verificationOutbox.enqueue(email, raw);
            count("identity.outbox", "purpose", purpose);
            return;
        }
    }

    private boolean canResendVerification(UUID userId) {
        var now = Instant.now();
        var attempts = jdbc.queryForObject(
                "select count(*) from verification_resend_attempt where user_id=? and requested_at>=?",
                Long.class,
                userId,
                timestamp(now.minusSeconds(props.verificationResendWindowSeconds()))
        );
        if (attempts != null && attempts >= props.verificationResendLimit()) return false;

        jdbc.update(
                "insert into verification_resend_attempt(id,user_id,requested_at) values(?,?,?)",
                UUID.randomUUID(),
                userId,
                timestamp(now)
        );
        return true;
    }

    private void revokeAll(UUID id) {
        jdbc.update("update user_session set revoked_at=? where user_id=? and revoked_at is null", timestamp(Instant.now()), id);
    }

    private void audit(UUID actor, String action, UUID target) {
        jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,'USER_ACCOUNT',?,?,?)", UUID.randomUUID(), actor, action, target, correlationId(), timestamp(Instant.now()));
        log.info("identity_event action={} actorUserId={} targetUserId={} correlationId={}", action, actor, target, correlationId());
    }

    private void count(String name, String... tags) {
        metrics.counter(name, tags).increment();
    }

    private void logFailure(String error, UUID userId) {
        log.warn("identity_failure error={} userId={} correlationId={}", error, userId, correlationId());
    }

    private String eventForTokenConfirmation(String purpose) {
        return "EMAIL_VERIFICATION".equals(purpose) ? "EMAIL_VERIFIED" : "TOKEN_CONFIRMED";
    }

    private UUID correlationId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            var value = servletAttributes.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
            if (value instanceof UUID correlationId) return correlationId;
        }
        return null;
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private String random() {
        var b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String hash(String s) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void validatePassword(String p) {
        if (p == null || !p.matches("(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}"))
            throw new PasswordPolicyException();
    }

    public record Login(String accessToken, String refreshToken, UUID sessionId, UserInfo user) {
    }

    public record UserInfo(UUID id, String fullName, String email, String role, String status) {
    }
}
