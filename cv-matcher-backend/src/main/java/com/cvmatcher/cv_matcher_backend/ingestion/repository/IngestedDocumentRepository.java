package com.cvmatcher.cv_matcher_backend.ingestion.repository;

import com.cvmatcher.cv_matcher_backend.ingestion.domain.IngestedDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface IngestedDocumentRepository extends JpaRepository<IngestedDocument, UUID> { Optional<IngestedDocument> findBySha256(String sha256); }
