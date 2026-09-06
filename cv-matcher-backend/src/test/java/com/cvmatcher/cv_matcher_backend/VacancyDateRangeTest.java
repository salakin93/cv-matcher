package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.vacancy.application.VacancyDateRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VacancyDateRangeTest {
    @Test
    void convertsInclusiveLaPazDatesToAnExclusiveUtcRange() {
        var range = VacancyDateRange.from(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"));

        assertEquals(Instant.parse("2026-09-01T04:00:00Z"), range.fromUtc());
        assertEquals(Instant.parse("2026-09-16T04:00:00Z"), range.toUtcExclusive());
        assertEquals(LocalDate.parse("2026-09-01"), range.dateFrom());
        assertEquals(LocalDate.parse("2026-09-15"), range.dateTo());
    }

    @Test
    void rejectsAnInvertedDateRange() {
        assertThrows(IllegalArgumentException.class, () ->
                VacancyDateRange.from(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-01"))
        );
    }
}
