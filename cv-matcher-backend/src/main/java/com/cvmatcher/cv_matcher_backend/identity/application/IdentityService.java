package com.cvmatcher.cv_matcher_backend.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;
    private final MailGateway mail;
    private final SecurityProperties props;
    private final JwtService jwt;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public IdentityService(JdbcTemplate jdbc, MailGateway mail, SecurityProperties props, JwtService jwt) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.props = props;
        this.jwt = jwt;
    }

    @Transactional
    public void register(String name, String email, String password) {
        validatePassword(password);
        var normalized = normalize(email);
        if (exists(normalized)) return;
        var id = UUID.randomUUID();
        var now = Instant.now();
        jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,created_at,updated_at) values(?,?,?,?,?,'RECRUITER','PENDING_VERIFICATION',?,?)", id, name.trim(), email.trim(), normalized, encoder.encode(password), now, now);
        sendToken(id, "EMAIL_VERIFICATION", null, props.verificationHours() * 3600);
        audit(null, "ACCOUNT_REGISTERED", id);
    }

    @Transactional
    public void confirm(String raw, String purpose, String password) {
        var row = jdbc.queryForList("select id,user_id,target_email,expires_at,consumed_at,purpose from account_action_token where token_hash=?", hash(raw)).stream().findFirst().orElse(null);
        if (row == null || !purpose.equals(row.get("purpose")) || ((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now()) || row.get("consumed_at") != null)
            throw new IllegalArgumentException("Invalid or expired token");
        var user = UUID.fromString(row.get("user_id").toString());
        if ("EMAIL_VERIFICATION".equals(purpose)) {
            jdbc.update("update user_account set status='ACTIVE',email_verified_at=?,updated_at=? where id=?", Instant.now(), Instant.now(), user);
        } else if ("PASSWORD_RESET".equals(purpose)) {
            validatePassword(password);
            jdbc.update("update user_account set password_hash=?,force_password_change=false,updated_at=? where id=?", encoder.encode(password), Instant.now(), user);
            revokeAll(user);
        } else {
            var target = (String) row.get("target_email");
            if (exists(target)) throw new IllegalStateException("Email already in use");
            jdbc.update("update user_account set email=?,email_normalized=?,email_verified_at=?,updated_at=? where id=?", target, target, Instant.now(), Instant.now(), user);
            revokeAll(user);
        }
        if (jdbc.update("update account_action_token set consumed_at=? where id=? and consumed_at is null", Instant.now(), UUID.fromString(row.get("id").toString())) != 1)
            throw new IllegalArgumentException("Invalid or expired token");
        audit(user, "TOKEN_CONFIRMED", user);
    }

    @Transactional
    public Login login(String email, String password) {
        var normalizedEmail = normalize(email);

        var user = jdbc.queryForList(
                "select * from user_account where email_normalized=?",
                normalizedEmail
        ).stream().findFirst().orElse(null);

        if (user == null) {
            throw new SecurityException("Invalid credentials");
        }

        var id = UUID.fromString(user.get("id").toString());
        var locked = (java.sql.Timestamp) user.get("locked_until");

        if (locked != null && locked.toInstant().isAfter(Instant.now())) {
            throw new SecurityException("Invalid credentials");
        }

        var status = (String) user.get("status");
        var passwordHash = (String) user.get("password_hash");

        if (!"ACTIVE".equals(status) || !encoder.matches(password, passwordHash)) {
            var attempts = ((Number) user.get("failed_login_attempts")).intValue() + 1;
            var lockedUntil = attempts >= 5 ? Instant.now().plusSeconds(900) : null;

            jdbc.update(
                    "update user_account set failed_login_attempts=?, locked_until=?, updated_at=? where id=?",
                    attempts,
                    lockedUntil,
                    Instant.now(),
                    id
            );

            throw new SecurityException("Invalid credentials");
        }

        var sessionId = UUID.randomUUID();
        var refreshToken = random();

        jdbc.update(
                "insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)",
                sessionId,
                id,
                hash(refreshToken),
                Instant.now().plusSeconds(props.sessionHours() * 3600),
                Instant.now()
        );

        jdbc.update(
                "update user_account set failed_login_attempts=0, locked_until=null where id=?",
                id
        );

        audit(id, "LOGIN_SUCCEEDED", id);

        var role = (String) user.get("role");
        var forcePasswordChange = Boolean.TRUE.equals(user.get("force_password_change"));

        return new Login(
                jwt.issue(id, role, sessionId),
                refreshToken,
                sessionId,
                role,
                forcePasswordChange
        );
    }

    @Transactional
    public Login refresh(String raw) {
        var s = jdbc.queryForList("select s.*,u.role,u.force_password_change from user_session s join user_account u on u.id=s.user_id where s.refresh_token_hash=?", hash(raw)).stream().findFirst().orElse(null);
        if (s == null) throw new SecurityException("Invalid session");
        var user = UUID.fromString(s.get("user_id").toString());
        if (s.get("revoked_at") != null) {
            revokeAll(user);
            audit(user, "REFRESH_TOKEN_REUSE", user);
            throw new SecurityException("Invalid session");
        }
        if (((java.sql.Timestamp) s.get("expires_at")).toInstant().isBefore(Instant.now()))
            throw new SecurityException("Invalid session");
        var next = random();
        var id = UUID.fromString(s.get("id").toString());
        if (jdbc.update("update user_session set revoked_at=? where id=? and revoked_at is null", Instant.now(), id) != 1) {
            revokeAll(user);
            throw new SecurityException("Invalid session");
        }
        var nextId = UUID.randomUUID();
        jdbc.update("insert into user_session(id,user_id,refresh_token_hash,expires_at,created_at) values(?,?,?,?,?)", nextId, user, hash(next), ((java.sql.Timestamp) s.get("expires_at")).toInstant(), Instant.now());
        audit(user, "REFRESH_ROTATED", user);
        return new Login(jwt.issue(user, (String) s.get("role"), nextId), next, nextId, (String) s.get("role"), Boolean.TRUE.equals(s.get("force_password_change")));
    }

    @Transactional
    public void requestToken(String email, String purpose, String target) {
        var u = jdbc.query("select id from user_account where email_normalized=?", rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, normalize(email));
        if (u != null)
            sendToken(u, purpose, target, "PASSWORD_RESET".equals(purpose) ? props.resetMinutes() * 60 : props.verificationHours() * 3600);
    }

    @Transactional
    public void logout(UUID id) {
        jdbc.update("update user_session set revoked_at=? where id=? and revoked_at is null", Instant.now(), id);
    }

    @Transactional
    public void logout(String raw) {
        var id = jdbc.query("select id from user_session where refresh_token_hash=?", rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, hash(raw));
        if (id != null) logout(id);
    }

    @Transactional
    public void changePassword(UUID userId, String current, String replacement) {
        var hash = jdbc.queryForObject("select password_hash from user_account where id=?", String.class, userId);
        if (!encoder.matches(current, hash)) throw new SecurityException("Invalid credentials");
        validatePassword(replacement);
        jdbc.update("update user_account set password_hash=?,force_password_change=false,updated_at=? where id=?", encoder.encode(replacement), Instant.now(), userId);
        revokeAll(userId);
        audit(userId, "PASSWORD_CHANGED", userId);
    }

    @Transactional
    public void requestEmailChange(UUID userId, String current, String email) {
        var hash = jdbc.queryForObject("select password_hash from user_account where id=?", String.class, userId);
        if (!encoder.matches(current, hash)) throw new SecurityException("Invalid credentials");
        var normalized = normalize(email);
        if (exists(normalized)) throw new IllegalStateException("Email already in use");
        sendToken(userId, "EMAIL_CHANGE", normalized, props.verificationHours() * 3600);
    }

    public UserInfo me(UUID userId) {
        var row = jdbc.queryForMap("select id,full_name,email,role,status,email_verified_at,force_password_change from user_account where id=?", userId);
        return new UserInfo(UUID.fromString(row.get("id").toString()), (String) row.get("full_name"), (String) row.get("email"), (String) row.get("role"), (String) row.get("status"), Boolean.TRUE.equals(row.get("force_password_change")));
    }

    public boolean exists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from user_account where email_normalized=?)", Boolean.class, email));
    }

    private void sendToken(UUID id, String purpose, String target, long seconds) {
        jdbc.update("update account_action_token set consumed_at=? where user_id=? and purpose=? and consumed_at is null", Instant.now(), id, purpose);
        var raw = random();
        jdbc.update("insert into account_action_token(id,user_id,token_hash,purpose,target_email,expires_at,created_at) values(?,?,?,?,?,?,?)", UUID.randomUUID(), id, hash(raw), purpose, target, Instant.now().plusSeconds(seconds), Instant.now());
        var email = jdbc.queryForObject("select email from user_account where id=?", String.class, id);
        mail.send(purpose, email, raw);
    }

    private void revokeAll(UUID id) {
        jdbc.update("update user_session set revoked_at=? where user_id=? and revoked_at is null", Instant.now(), id);
    }

    private void audit(UUID actor, String action, UUID target) {
        jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,created_at) values(?,?,?,'USER_ACCOUNT',?,?)", UUID.randomUUID(), actor, action, target, Instant.now());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
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
            throw new IllegalArgumentException("Password does not meet policy");
    }

    public record Login(String accessToken, String refreshToken, UUID sessionId, String role,
                        boolean forcePasswordChange) {
    }

    public record UserInfo(UUID id, String fullName, String email, String role, String status,
                           boolean forcePasswordChange) {
    }
}
