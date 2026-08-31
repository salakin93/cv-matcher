package com.cvmatcher.cv_matcher_backend.matchingjob.repository;

import com.cvmatcher.cv_matcher_backend.matchingjob.domain.MatchingJobEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingJobEventRepository extends JpaRepository<MatchingJobEvent, UUID> {
}
