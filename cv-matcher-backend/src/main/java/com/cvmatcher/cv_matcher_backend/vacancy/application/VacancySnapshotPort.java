package com.cvmatcher.cv_matcher_backend.vacancy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public application boundary used by report jobs; it deliberately exposes no vacancy persistence. */
public interface VacancySnapshotPort {
    VacancySnapshot snapshotActive(UUID vacancyId);
    boolean exists(UUID vacancyId);

    record VacancySnapshot(UUID id, long version, String title, Instant receivedFromUtc,
                           Instant receivedToUtcExclusive, List<RequirementSnapshot> requirements) {}
    record RequirementSnapshot(String description, int weight, boolean mandatory, int position) {}
}
