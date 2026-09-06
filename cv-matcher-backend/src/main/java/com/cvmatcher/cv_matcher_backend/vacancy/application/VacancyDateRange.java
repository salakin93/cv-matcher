package com.cvmatcher.cv_matcher_backend.vacancy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public record VacancyDateRange(Instant fromUtc, Instant toUtcExclusive) {
    public static final ZoneId LA_PAZ = ZoneId.of("America/La_Paz");

    public static VacancyDateRange from(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom.isAfter(dateTo)) throw new IllegalArgumentException("dateFrom must be before dateTo");
        return new VacancyDateRange(
                dateFrom.atStartOfDay(LA_PAZ).toInstant(),
                dateTo.plusDays(1).atStartOfDay(LA_PAZ).toInstant()
        );
    }

    public LocalDate dateFrom() {
        return fromUtc.atZone(LA_PAZ).toLocalDate();
    }

    public LocalDate dateTo() {
        return toUtcExclusive.atZone(LA_PAZ).toLocalDate().minusDays(1);
    }
}
