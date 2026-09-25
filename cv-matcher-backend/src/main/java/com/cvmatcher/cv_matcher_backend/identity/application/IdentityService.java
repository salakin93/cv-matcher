package com.cvmatcher.cv_matcher_backend.identity.application;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentityService {
    private static final String ACTIVE = "ACTIVE";
    private final JdbcTemplate jdbc;
    private final SecurityProperties props;
    private final JwtService jwt;
    private final MeterRegistry metrics;
    private final VerificationOutbox outbox;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public IdentityService(JdbcTemplate jdbc, SecurityProperties props, JwtService jwt, MeterRegistry metrics, VerificationOutbox outbox) {
        this.jdbc = jdbc;
        this.props = props;
        this.jwt = jwt;
        this.metrics = metrics;
        this.outbox = outbox;
    }

    @Transactional
    public void register(String name, String email, String password) {
        validatePassword(password);
        var normalized = normalize(email);
        if (exists(normalized)) return;
        var id = UUID.randomUUID();
        var now = now();
        if (jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,created_at,updated_at) values(?,?,?,?,?,'RECRUITER','PENDING_VERIFICATION',?,?) on conflict (email_normalized) do nothing", id, name.trim(), email.trim(), normalized, encoder.encode(password), now, now) == 0) return;
        sendVerification(id);
        audit(null, "ACCOUNT_REGISTERED", id);
        count("identity.registrations");
    }

    @Transactional
    public void confirmVerification(String raw) {
        var token = jdbc.queryForList("select id,user_id,expires_at,consumed_at from account_action_token where token_hash=? for update", hash(raw)).stream().findFirst().orElse(null);
        if (!valid(token)) throw new IllegalArgumentException();
        var userId = uuid(token.get("user_id"));
        if (jdbc.update("update account_action_token set consumed_at=? where id=? and consumed_at is null", now(), uuid(token.get("id"))) != 1) throw new IllegalArgumentException();
        if (jdbc.update("update user_account set status='ACTIVE',email_verified_at=coalesce(email_verified_at,?),updated_at=?,version=version+1 where id=? and status='PENDING_VERIFICATION'", now(), now(), userId) != 1) throw new IllegalArgumentException();
        audit(userId, "EMAIL_VERIFIED", userId);
    }

    @Transactional(noRollbackFor = SecurityException.class)
    public Login login(String email, String password) {
        var user = jdbc.queryForList("select * from user_account where email_normalized=? for update", normalize(email)).stream().findFirst().orElse(null);
        if (user == null) throw invalidCredentials();
        var id = uuid(user.get("id"));
        var matches = encoder.matches(password, (String) user.get("password_hash"));
        var locked = (Timestamp) user.get("locked_until");
        if (locked != null && locked.toInstant().isAfter(Instant.now())) {
            if (matches) throw new AccountAccessException(AccountAccessException.Reason.ACCOUNT_TEMPORARILY_LOCKED);
            throw invalidCredentials();
        }
        if ("PENDING_VERIFICATION".equals(user.get("status")) && matches) throw new AccountAccessException(AccountAccessException.Reason.EMAIL_VERIFICATION_REQUIRED);
        if (!ACTIVE.equals(user.get("status")) || !matches) {
            if (!matches) failedLogin(id, ((Number) user.get("failed_login_attempts")).intValue() + 1);
            throw invalidCredentials();
        }
        jdbc.update("update user_account set failed_login_attempts=0,locked_until=null,updated_at=? where id=?", now(), id);
        return createSession(user, id);
    }

    @Transactional(noRollbackFor = SecurityException.class)
    public Login refresh(String raw) {
        var session = jdbc.queryForList("select s.*,u.full_name,u.email,u.role,u.status,u.password_change_required from user_session s join user_account u on u.id=s.user_id where s.refresh_token_hash=? for update", hash(raw)).stream().findFirst().orElse(null);
        if (session == null || session.get("revoked_at") != null || !ACTIVE.equals(session.get("status")) || !afterNow(session.get("expires_at"))) throw invalidSession();
        if (Boolean.TRUE.equals(session.get("password_change_required"))) throw invalidSession();
        var userId = uuid(session.get("user_id"));
        if (jdbc.update("update user_session set revoked_at=? where id=? and revoked_at is null", now(), uuid(session.get("id"))) != 1) {
            revokeAll(userId);
            throw invalidSession();
        }
        var rawNext = random();
        var sessionId = UUID.randomUUID();
        jdbc.update("insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)", sessionId, userId, hash(rawNext), session.get("expires_at"), now());
        return loginFor(session, userId, sessionId, rawNext);
    }

    @Transactional
    public void requestVerification(String email) {
        var account = accountByEmail(email, true);
        if (account != null && "PENDING_VERIFICATION".equals(account.get("status")) && allowed(uuid(account.get("id")), "EMAIL_VERIFICATION", props.verificationResendLimit(), props.verificationResendWindowSeconds())) sendVerification(uuid(account.get("id")));
    }

    @Transactional
    public void requestPasswordReset(String email) {
        var account = accountByEmail(email, true);
        if (account == null) return;
        var id = uuid(account.get("id"));
        if ("PENDING_VERIFICATION".equals(account.get("status"))) {
            if (allowed(id, "EMAIL_VERIFICATION", props.verificationResendLimit(), props.verificationResendWindowSeconds())) sendVerification(id);
            return;
        }
        if (ACTIVE.equals(account.get("status")) && allowed(id, "PASSWORD_RESET", props.passwordResetRequestLimit(), props.passwordResetRequestWindowSeconds())) sendPasswordReset(id);
    }

    @Transactional
    public void confirmPasswordReset(String raw, String password) {
        validatePassword(password);
        var reset = jdbc.queryForList("select id,user_id,expires_at,consumed_at from password_reset where token_hash=? for update", hash(raw)).stream().findFirst().orElse(null);
        if (!valid(reset) || jdbc.update("update password_reset set consumed_at=? where id=? and consumed_at is null", now(), uuid(reset.get("id"))) != 1) throw new IllegalArgumentException();
        var id = uuid(reset.get("user_id"));
        jdbc.update("update user_account set password_hash=?,failed_login_attempts=0,locked_until=null,password_change_required=false,updated_at=?,version=version+1 where id=?", encoder.encode(password), now(), id);
        revokeAll(id);
        audit(id, "PASSWORD_RESET_COMPLETED", id);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String password) {
        validatePassword(password);
        var account = account(userId, true);
        if (account == null || !encoder.matches(currentPassword, (String) account.get("password_hash"))) throw new SecurityException();
        if (Boolean.TRUE.equals(account.get("password_change_required")) && encoder.matches(password, (String) account.get("password_hash"))) throw new PasswordPolicyException();
        jdbc.update("update user_account set password_hash=?,password_change_required=false,failed_login_attempts=0,locked_until=null,updated_at=?,version=version+1 where id=?", encoder.encode(password), now(), userId);
        revokeAll(userId);
        audit(userId, "PASSWORD_CHANGED", userId);
    }

    @Transactional
    public void requestEmailChange(UUID userId, String currentPassword, String email) {
        var account = account(userId, true);
        if (account == null || !encoder.matches(currentPassword, (String) account.get("password_hash"))) throw new SecurityException();
        var normalized = normalize(email);
        if (normalized.equals(account.get("email_normalized")) || exists(normalized)) throw new IllegalStateException();
        jdbc.update("update email_change set consumed_at=? where user_id=? and consumed_at is null", now(), userId);
        sendEmailChange(userId, email.trim(), normalized);
    }

    @Transactional
    public void resendEmailChange(UUID userId) {
        var change = jdbc.queryForList("select target_email,target_email_normalized from email_change where user_id=? and consumed_at is null order by created_at desc for update", userId).stream().findFirst().orElse(null);
        if (change != null && allowed(userId, "EMAIL_CHANGE", props.emailChangeResendLimit(), props.emailChangeResendWindowSeconds())) {
            jdbc.update("update email_change set consumed_at=? where user_id=? and consumed_at is null", now(), userId);
            sendEmailChange(userId, (String) change.get("target_email"), (String) change.get("target_email_normalized"));
        }
    }

    @Transactional
    public void confirmEmailChange(String raw) {
        var change = jdbc.queryForList("select id,user_id,target_email,target_email_normalized,expires_at,consumed_at from email_change where token_hash=? for update", hash(raw)).stream().findFirst().orElse(null);
        if (!valid(change) || exists((String) change.get("target_email_normalized")) || jdbc.update("update email_change set consumed_at=? where id=? and consumed_at is null", now(), uuid(change.get("id"))) != 1) throw new IllegalArgumentException();
        var id = uuid(change.get("user_id"));
        jdbc.update("update user_account set email=?,email_normalized=?,updated_at=?,version=version+1 where id=?", change.get("target_email"), change.get("target_email_normalized"), now(), id);
        revokeAll(id);
        audit(id, "EMAIL_CHANGED", id);
    }

    @Transactional
    public void logout(String raw) {
        var session = jdbc.query("select id,user_id from user_session where refresh_token_hash=?", rs -> rs.next() ? new UUID[]{UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2))} : null, hash(raw));
        if (session != null) jdbc.update("update user_session set revoked_at=? where id=? and revoked_at is null", now(), session[0]);
    }

    public UserInfo me(UUID userId) { return userInfo(account(userId, false)); }

    public List<UserInfo> users(int limit, int offset) {
        return jdbc.queryForList("select id,full_name,email,role,status,password_change_required,version from user_account order by created_at,id limit ? offset ?", limit, offset).stream().map(this::userInfo).toList();
    }

    @Transactional
    public UserInfo updateUser(UUID actor, UUID target, String role, Boolean active, long version) {
        if (actor.equals(target)) throw new IllegalStateException();
        var activeAdmins = lockAndCountActiveAdmins();
        var account = account(target, true);
        if (account == null || ((Number) account.get("version")).longValue() != version) throw new IllegalStateException();
        var nextRole = role == null ? (String) account.get("role") : role;
        var nextStatus = active == null ? (String) account.get("status") : active
                ? account.get("email_verified_at") == null ? (String) account.get("status") : ACTIVE
                : "DISABLED";
        if (!nextRole.equals(account.get("role")) && !"RECRUITER".equals(nextRole) && !"ADMIN".equals(nextRole)) throw new IllegalArgumentException();
        if (nextRole.equals(account.get("role")) && nextStatus.equals(account.get("status"))) return userInfo(account);
        if ("ADMIN".equals(account.get("role")) && ACTIVE.equals(account.get("status")) && (!"ADMIN".equals(nextRole) || !ACTIVE.equals(nextStatus)) && activeAdmins <= 1) throw new IllegalStateException();
        if (jdbc.update("update user_account set role=?,status=?,updated_at=?,version=version+1 where id=? and version=?", nextRole, nextStatus, now(), target, version) != 1) throw new IllegalStateException();
        revokeAll(target);
        if (!nextRole.equals(account.get("role"))) audit(actor, "USER_ROLE_CHANGED", target);
        if (!nextStatus.equals(account.get("status"))) audit(actor, ACTIVE.equals(nextStatus) ? "USER_ACTIVATED" : "USER_DEACTIVATED", target);
        outbox.enqueue((String) account.get("email"), !nextRole.equals(account.get("role")) ? "ACCOUNT_ROLE_CHANGED" : ACTIVE.equals(nextStatus) ? "ACCOUNT_ACTIVATED" : "ACCOUNT_DEACTIVATED", "account-security-notification");
        return userInfo(account(target, false));
    }

    public boolean exists(String email) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from user_account where email_normalized=?)", Boolean.class, email)); }

    private Login createSession(Map<String, Object> user, UUID id) {
        var raw = random(); var session = UUID.randomUUID();
        jdbc.update("insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)", session, id, hash(raw), Timestamp.from(Instant.now().plusSeconds(props.sessionHours() * 3600)), now());
        return loginFor(user, id, session, raw);
    }
    private Login loginFor(Map<String, Object> user, UUID id, UUID session, String raw) { return new Login(jwt.issue(id, (String) user.get("role"), session), raw, session, userInfo(user)); }
    private void sendVerification(UUID id) { sendActionToken(id, "EMAIL_VERIFICATION", props.verificationHours() * 3600); }
    private void sendActionToken(UUID id, String purpose, long seconds) {
        jdbc.update("update account_action_token set consumed_at=? where user_id=? and purpose=? and consumed_at is null", now(), id, purpose);
        var raw = random(); jdbc.update("insert into account_action_token(id,user_id,token_hash,purpose,expires_at,created_at) values(?,?,?,?,?,?)", UUID.randomUUID(), id, hash(raw), purpose, Timestamp.from(Instant.now().plusSeconds(seconds)), now());
        outbox.enqueue(jdbc.queryForObject("select email from user_account where id=?", String.class, id), purpose, raw);
    }
    private void sendPasswordReset(UUID id) {
        jdbc.update("update password_reset set consumed_at=? where user_id=? and consumed_at is null", now(), id);
        var raw = random(); jdbc.update("insert into password_reset(id,user_id,token_hash,expires_at,created_at) values(?,?,?,?,?)", UUID.randomUUID(), id, hash(raw), Timestamp.from(Instant.now().plusSeconds(props.passwordResetMinutes() * 60)), now());
        outbox.enqueue(jdbc.queryForObject("select email from user_account where id=?", String.class, id), "PASSWORD_RESET", raw);
    }
    private void sendEmailChange(UUID id, String email, String normalized) {
        var raw = random(); jdbc.update("insert into email_change(id,user_id,target_email,target_email_normalized,token_hash,expires_at,created_at) values(?,?,?,?,?,?,?)", UUID.randomUUID(), id, email, normalized, hash(raw), Timestamp.from(Instant.now().plusSeconds(props.emailChangeHours() * 3600)), now());
        outbox.enqueue(email, "EMAIL_CHANGE", raw);
    }
    private boolean allowed(UUID id, String purpose, long limit, long window) {
        String table = "EMAIL_VERIFICATION".equals(purpose) ? "verification_resend_attempt" : "account_security_request";
        String where = "EMAIL_VERIFICATION".equals(purpose) ? "user_id=? and requested_at>=?" : "user_id=? and purpose=? and requested_at>=?";
        Object[] params = "EMAIL_VERIFICATION".equals(purpose) ? new Object[]{id, Timestamp.from(Instant.now().minusSeconds(window))} : new Object[]{id, purpose, Timestamp.from(Instant.now().minusSeconds(window))};
        var attempts = jdbc.queryForObject("select count(*) from " + table + " where " + where, Long.class, params);
        if (attempts != null && attempts >= limit) return false;
        if ("EMAIL_VERIFICATION".equals(purpose)) jdbc.update("insert into verification_resend_attempt(id,user_id,requested_at) values(?,?,?)", UUID.randomUUID(), id, now());
        else jdbc.update("insert into account_security_request(id,user_id,purpose,requested_at) values(?,?,?,?)", UUID.randomUUID(), id, purpose, now());
        return true;
    }
    private void failedLogin(UUID id, int attempts) { var lock = attempts >= props.loginMaxFailedAttempts() ? Timestamp.from(Instant.now().plusSeconds(props.loginLockMinutes() * 60)) : null; jdbc.update("update user_account set failed_login_attempts=?,locked_until=?,updated_at=? where id=?", attempts, lock, now(), id); if (attempts == props.loginMaxFailedAttempts()) audit(id, "LOGIN_LOCKED", id); }
    private void revokeAll(UUID id) { jdbc.update("update user_session set revoked_at=? where user_id=? and revoked_at is null", now(), id); }
    private long lockAndCountActiveAdmins() { return jdbc.queryForList("select id from user_account where role='ADMIN' and status='ACTIVE' order by id for update").size(); }
    private Map<String,Object> account(UUID id, boolean lock) { var rows = jdbc.queryForList("select * from user_account where id=?" + (lock ? " for update" : ""), id); return rows.isEmpty() ? null : rows.getFirst(); }
    private Map<String,Object> accountByEmail(String email, boolean lock) { var rows = jdbc.queryForList("select * from user_account where email_normalized=?" + (lock ? " for update" : ""), normalize(email)); return rows.isEmpty() ? null : rows.getFirst(); }
    private UserInfo userInfo(Map<String,Object> row) { if (row == null) throw new SecurityException(); return new UserInfo(uuid(row.get("id")), (String) row.get("full_name"), (String) row.get("email"), (String) row.get("role"), (String) row.get("status"), Boolean.TRUE.equals(row.get("password_change_required")), row.get("version") == null ? 0 : ((Number) row.get("version")).longValue()); }
    private boolean valid(Map<String,Object> token) { return token != null && token.get("consumed_at") == null && afterNow(token.get("expires_at")); }
    private boolean afterNow(Object value) { return ((Timestamp) value).toInstant().isAfter(Instant.now()); }
    private SecurityException invalidCredentials() { count("identity.logins", "outcome", "failure"); return new SecurityException(); }
    private SecurityException invalidSession() { count("identity.refreshes", "outcome", "failure"); return new SecurityException(); }
    private void audit(UUID actor, String action, UUID target) { jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,'USER_ACCOUNT',?,?,?)", UUID.randomUUID(), actor, action, target, correlationId(), now()); }
    private void count(String name, String... tags) { metrics.counter(name, tags).increment(); }
    private UUID correlationId() { var a = RequestContextHolder.getRequestAttributes(); if (a instanceof ServletRequestAttributes s && s.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE) instanceof UUID id) return id; return null; }
    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private Timestamp now() { return Timestamp.from(Instant.now()); }
    private UUID uuid(Object value) { return UUID.fromString(value.toString()); }
    private String random() { var bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) { try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private void validatePassword(String password) { if (password == null || !password.matches("(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}")) throw new PasswordPolicyException(); }
    public record Login(String accessToken, String refreshToken, UUID sessionId, UserInfo user) { }
    public record UserInfo(UUID id, String fullName, String email, String role, String status, boolean passwordChangeRequired, long version) { }
}
