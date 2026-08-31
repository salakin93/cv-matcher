package com.cvmatcher.cv_matcher_backend.ingestion.repository;

import com.cvmatcher.cv_matcher_backend.ingestion.domain.MatchingJobDocumentReference;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingJobDocumentReferenceRepository extends JpaRepository<MatchingJobDocumentReference, UUID> {}
