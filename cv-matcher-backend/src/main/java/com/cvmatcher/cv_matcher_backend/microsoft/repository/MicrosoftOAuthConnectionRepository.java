package com.cvmatcher.cv_matcher_backend.microsoft.repository;

import com.cvmatcher.cv_matcher_backend.microsoft.domain.MicrosoftOAuthConnection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MicrosoftOAuthConnectionRepository extends JpaRepository<MicrosoftOAuthConnection, UUID> {
    Optional<MicrosoftOAuthConnection> findByActiveTrue();
    List<MicrosoftOAuthConnection> findAllByActiveTrue();
}
