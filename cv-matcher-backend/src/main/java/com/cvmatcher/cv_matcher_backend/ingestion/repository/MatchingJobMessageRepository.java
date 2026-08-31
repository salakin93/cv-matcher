package com.cvmatcher.cv_matcher_backend.ingestion.repository;

import com.cvmatcher.cv_matcher_backend.ingestion.domain.MatchingJobMessage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingJobMessageRepository extends JpaRepository<MatchingJobMessage, UUID> {
    Optional<MatchingJobMessage> findByMatchingJobIdAndGraphMessageId(UUID matchingJobId, String graphMessageId);
}
