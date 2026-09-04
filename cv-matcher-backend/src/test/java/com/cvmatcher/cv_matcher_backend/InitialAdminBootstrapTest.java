package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.bootstrap.InitialAdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialAdminBootstrapTest {

    @Test
    void skipsBootstrapOutsideProductionWhenCredentialsAreAbsent() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var environment = mock(Environment.class);
        when(jdbc.queryForObject("select count(*) from user_account", Long.class)).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        var properties = new SecurityProperties("a".repeat(32), 15, 8, 24, 30, "", "", false);

        new InitialAdminBootstrap(jdbc, properties, environment).run(new DefaultApplicationArguments(new String[0]));

        verify(jdbc, never()).update(anyString(), (Object[]) any());
    }

    @Test
    void refusesToStartProductionWithoutInitialAdminCredentials() {
        var jdbc = mock(JdbcTemplate.class);
        var environment = mock(Environment.class);
        when(jdbc.queryForObject("select count(*) from user_account", Long.class)).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        var properties = new SecurityProperties("a".repeat(32), 15, 8, 24, 30, "", "", false);

        assertThrows(IllegalStateException.class, () ->
                new InitialAdminBootstrap(jdbc, properties, environment).run(new DefaultApplicationArguments(new String[0]))
        );
    }

    @Test
    void bootstrapsAnActiveAdministratorAndAuditEventWhenTheDatabaseIsEmpty() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var environment = mock(Environment.class);
        when(jdbc.queryForObject("select count(*) from user_account", Long.class)).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        var properties = new SecurityProperties("a".repeat(32), 15, 8, 24, 30, "admin@example.test", "ClaveSegura1", true);

        new InitialAdminBootstrap(jdbc, properties, environment).run(new DefaultApplicationArguments(new String[0]));

        verify(jdbc).update(startsWith("insert into user_account"), (Object[]) any());
        verify(jdbc).update(startsWith("insert into audit_event"), (Object[]) any());
    }
}
