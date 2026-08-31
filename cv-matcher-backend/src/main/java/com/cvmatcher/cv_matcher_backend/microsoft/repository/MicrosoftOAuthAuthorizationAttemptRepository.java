package com.cvmatcher.cv_matcher_backend.microsoft.repository;

import com.cvmatcher.cv_matcher_backend.microsoft.domain.MicrosoftOAuthAuthorizationAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MicrosoftOAuthAuthorizationAttemptRepository extends JpaRepository<MicrosoftOAuthAuthorizationAttempt, UUID> {
    Optional<MicrosoftOAuthAuthorizationAttempt> findByStateHash(String stateHash);
}
