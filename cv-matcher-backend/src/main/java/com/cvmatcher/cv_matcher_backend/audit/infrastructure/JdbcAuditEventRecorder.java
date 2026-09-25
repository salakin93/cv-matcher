package com.cvmatcher.cv_matcher_backend.audit.infrastructure;

import com.cvmatcher.cv_matcher_backend.audit.application.AuditAction;
import com.cvmatcher.cv_matcher_backend.audit.application.AuditEventPort;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
public class JdbcAuditEventRecorder implements AuditEventPort {
    private static final String VACANCY_TARGET_TYPE = "VACANCY";
    private final JdbcTemplate jdbc;

    public JdbcAuditEventRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID actorUserId, AuditAction action, UUID targetId) {
        jdbc.update(
                "insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,?,?,?,?)",
                UUID.randomUUID(), actorUserId, action.name(), VACANCY_TARGET_TYPE, targetId, correlationId(), Timestamp.from(Instant.now())
        );
    }

    private UUID correlationId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            var value = servletAttributes.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
            if (value instanceof UUID correlationId) return correlationId;
        }
        return null;
    }
}
