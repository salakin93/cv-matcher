package com.cvmatcher.cv_matcher_backend.document;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfiguredAntivirusPortTest {
    @Test void failsClosedUnlessTheTestDoubleIsExplicitlyClean() {
        assertEquals(AntivirusPort.Result.CLEAN, new ConfiguredAntivirusPort(properties("clean")).scan(new byte[]{1}));
        assertEquals(AntivirusPort.Result.UNAVAILABLE, new ConfiguredAntivirusPort(properties("unavailable")).scan(new byte[]{1}));
    }

    private static DocumentIngestionProperties properties(String antivirusMode) {
        return new DocumentIngestionProperties(true, Duration.ofSeconds(1), Duration.ofMinutes(1), 100, 1, 100, 1, Path.of("build", "test-storage").toString(), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, antivirusMode, "localhost", 3310, Duration.ofSeconds(1));
    }
}
