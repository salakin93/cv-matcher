package com.cvmatcher.cv_matcher_backend.outlook.application;

import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

@Component
final class OutlookObservability {
    private static final UUID CONNECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    OutlookObservability(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        for (var status : List.of("NOT_CONNECTED", "CONNECTED", "REAUTHORIZATION_REQUIRED", "ERROR"))
            Gauge.builder("outlook.connection_status", () -> connectionStatus(status)).tag("status", status).register(metrics);
    }

    void authorization(String outcome) {
        metrics.counter("outlook.authorization_attempts", "outcome", outcome).increment();
    }

    void tokenRefresh(String outcome) {
        metrics.counter("outlook.token_refreshes", "outcome", outcome).increment();
    }

    void audit(UUID actor, String action) {
        jdbc.update("insert into audit_event(id,actor_user_id,action,target_type,target_id,correlation_id,created_at) values(?,?,?,'OUTLOOK_CONNECTION',?,?,current_timestamp)", UUID.randomUUID(), actor, action, CONNECTION_ID, correlationId());
    }

    private double connectionStatus(String status) {
        var current = jdbc.queryForObject("select status from outlook_connection where id=1", String.class);
        return status.equals(current) ? 1 : 0;
    }

    UUID correlationId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes request) {
            var value = request.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
            if (value instanceof UUID id) return id;
        }
        return null;
    }
}
