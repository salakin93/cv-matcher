package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestClientOutlookOAuthClientTest {
    private static final Duration MAXIMUM = Duration.ofSeconds(1);

    @Test
    void capsRetryAfterSecondsAtTheConfiguredMaximum() {
        assertEquals(1_000L, RestClientOutlookOAuthClient.retryDelayMilliseconds("2", 1, MAXIMUM));
    }

    @Test
    void capsRetryAfterDatesAtTheConfiguredMaximum() {
        var retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(60).atOffset(ZoneOffset.UTC));
        assertEquals(1_000L, RestClientOutlookOAuthClient.retryDelayMilliseconds(retryAfter, 1, MAXIMUM));
    }

    @Test
    void handlesExcessiveAndOverflowingRetryAfterValuesWithoutOverflowing() {
        assertEquals(1_000L, RestClientOutlookOAuthClient.retryDelayMilliseconds(Long.MAX_VALUE + "", 1, MAXIMUM));
        assertEquals(200L, RestClientOutlookOAuthClient.retryDelayMilliseconds("999999999999999999999999", 1, MAXIMUM));
    }
}
