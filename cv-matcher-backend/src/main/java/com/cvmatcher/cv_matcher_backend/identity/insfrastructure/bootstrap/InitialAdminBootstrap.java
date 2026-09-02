package com.cvmatcher.cv_matcher_backend.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Component
public class InitialAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SecurityProperties properties;
    private final Environment environment;

    public InitialAdminBootstrap(JdbcTemplate jdbc, SecurityProperties properties, Environment environment) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var accounts = jdbc.queryForObject("select count(*) from user_account", Long.class);
        if (accounts != null && accounts > 0) return;
        var email = properties.initialAdminEmail();
        var password = properties.initialAdminPassword();
        if (blank(email) || blank(password)) {
            if (Arrays.asList(environment.getActiveProfiles()).contains("prod"))
                throw new IllegalStateException("Initial administrator credentials are required in prod");
            return;
        }
        if (!password.matches("(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}"))
            throw new IllegalStateException("Initial administrator password does not meet policy");
        var now = Instant.now();
        var normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        var id = UUID.randomUUID();
        var hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(password);
        var timestamp = Timestamp.from(now);
        jdbc.update(
                "insert into user_account(" +
                        "id,full_name,email,email_normalized,password_hash," +
                        "role,status,email_verified_at,force_password_change,created_at,updated_at" +
                        ") values(?,?,?,?,?,'ADMIN','ACTIVE',?,true,?,?)",
                id,
                "Initial Administrator",
                email.trim(),
                normalized,
                hash,
                timestamp,
                timestamp,
                timestamp
        );

        jdbc.update(
                "insert into audit_event(" +
                        "id,action,target_type,target_id,created_at" +
                        ") values(?,'INITIAL_ADMIN_BOOTSTRAPPED','USER_ACCOUNT',?,?)",
                UUID.randomUUID(),
                id,
                timestamp
        );
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
