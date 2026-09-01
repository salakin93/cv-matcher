package com.cvmatcher.cv_matcher_backend.matchingjob.repository;

import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJob;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchingJobRepository extends JpaRepository<MatchingJob, UUID> {

    boolean existsByStatusIn(Collection<MatchingJobStatus> statuses);

    Optional<MatchingJob> findFirstByStatusInOrderByCreatedAtAsc(Collection<MatchingJobStatus> statuses);

    java.util.List<MatchingJob> findByStatusInOrderByCreatedAtAsc(Collection<MatchingJobStatus> statuses);

    @Modifying
    @Query(value = """
            UPDATE matching_job SET status = 'INGESTING_EMAILS', ingestion_claimed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :jobId AND status IN ('QUEUED', 'INGESTING_EMAILS') AND ingestion_claimed_at IS NULL
            """, nativeQuery = true)
    int claimQueuedJob(@Param("jobId") UUID jobId);

    @Modifying
    @Query(value = "UPDATE matching_job SET ingestion_claimed_at = NULL WHERE status = 'INGESTING_EMAILS'", nativeQuery = true)
    int releaseInterruptedClaims();
}
