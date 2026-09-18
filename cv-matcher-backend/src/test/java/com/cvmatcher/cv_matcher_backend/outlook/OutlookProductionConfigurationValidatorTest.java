package com.cvmatcher.cv_matcher_backend.outlook;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutlookProductionConfigurationValidatorTest {
    @Test
    void acceptsTheProductionMicrosoftGraphBaseUri() {
        assertDoesNotThrow(() -> validator("https://graph.microsoft.com").afterSingletonsInstantiated());
    }

    @Test
    void rejectsNonAllowlistedOrUnsafeGraphBaseUris() {
        for (var graphBaseUri : new String[]{"http://graph.microsoft.com", "https://graph.microsoft.com:444", "https://graph.microsoft.com/v1.0", "https://graph.microsoft.com?x=1", "https://example.test"}) {
            assertThrows(IllegalStateException.class, () -> validator(graphBaseUri).afterSingletonsInstantiated());
        }
    }

    @Test
    void rejectsANonpositiveRetryAfterMaximum() {
        var environment = mock(Environment.class);
        when(environment.matchesProfiles("prod")).thenReturn(true);
        var properties = new OutlookProperties("tenant", "client", "secret", "https://login.microsoftonline.com",
                "https://app.example.test/callback", "https://app.example.test", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, "https://graph.microsoft.com", Duration.ZERO);

        assertThrows(IllegalStateException.class, () -> new OutlookProductionConfigurationValidator(properties, environment).afterSingletonsInstantiated());
    }

    @Test
    void rejectsARetryAfterMaximumAboveTheApprovedBound() {
        var environment = mock(Environment.class);
        when(environment.matchesProfiles("prod")).thenReturn(true);
        var properties = new OutlookProperties("tenant", "client", "secret", "https://login.microsoftonline.com",
                "https://app.example.test/callback", "https://app.example.test", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, "https://graph.microsoft.com", Duration.ofMinutes(6));

        assertThrows(IllegalStateException.class, () -> new OutlookProductionConfigurationValidator(properties, environment).afterSingletonsInstantiated());
    }

    private static OutlookProductionConfigurationValidator validator(String graphBaseUri) {
        var environment = mock(Environment.class);
        when(environment.matchesProfiles("prod")).thenReturn(true);
        return new OutlookProductionConfigurationValidator(new OutlookProperties("tenant", "client", "secret",
                "https://login.microsoftonline.com", "https://app.example.test/callback", "https://app.example.test",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 3, graphBaseUri), environment);
    }
}
