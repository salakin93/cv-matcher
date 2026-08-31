package com.cvmatcher.cv_matcher_backend.matchingjob.repository;

import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJob;
import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingJobRepository extends JpaRepository<MatchingJob, UUID> {

    boolean existsByStatusIn(Collection<MatchingJobStatus> statuses);

    Optional<MatchingJob> findFirstByStatusInOrderByCreatedAtAsc(Collection<MatchingJobStatus> statuses);

    java.util.List<MatchingJob> findByStatusInOrderByCreatedAtAsc(Collection<MatchingJobStatus> statuses);
}
