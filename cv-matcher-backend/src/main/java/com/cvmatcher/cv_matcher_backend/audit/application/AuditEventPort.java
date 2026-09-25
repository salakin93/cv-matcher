package com.cvmatcher.cv_matcher_backend.audit.application;

import java.util.UUID;

public interface AuditEventPort {
    void record(UUID actorUserId, AuditAction action, UUID targetId);
}
