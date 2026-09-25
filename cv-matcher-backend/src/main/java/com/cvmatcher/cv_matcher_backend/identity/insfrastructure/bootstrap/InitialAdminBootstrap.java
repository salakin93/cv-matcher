package com.cvmatcher.cv_matcher_backend.identity.insfrastructure.bootstrap;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Component
class InitialAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SecurityProperties properties;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    InitialAdminBootstrap(JdbcTemplate jdbc, SecurityProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.initialAdminEmail() == null || properties.initialAdminEmail().isBlank()
                || properties.initialAdminPassword() == null || properties.initialAdminPassword().isBlank()) return;
        if (!properties.initialAdminPassword().matches("(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}")) {
            throw new IllegalStateException("Initial administrator password does not satisfy the password policy");
        }
        if (jdbc.update("insert into identity_bootstrap(id,completed_at) values(true,?) on conflict do nothing", Timestamp.from(Instant.now())) == 0) return;

        var now = Timestamp.from(Instant.now());
        var email = properties.initialAdminEmail().trim();
        var id = UUID.randomUUID();
        jdbc.update("insert into user_account(id,full_name,email,email_normalized,password_hash,role,status,email_verified_at,password_change_required,created_at,updated_at) values(?,?,?,?,?,'ADMIN','ACTIVE',?,true,?,?)",
                id, "Administrador inicial", email, email.toLowerCase(Locale.ROOT), encoder.encode(properties.initialAdminPassword()), now, now, now);
        jdbc.update("insert into audit_event(id,action,target_type,target_id,created_at) values(?,'INITIAL_ADMIN_PROVISIONED','USER_ACCOUNT',?,?)", UUID.randomUUID(), id, now);
    }
}
