package com.utewedding.app.service.impl;

import com.utewedding.app.repository.AuthSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits family revocation even when the refresh request returns 401. */
@Service
public class SessionFamilyRevocationService {
    private final AuthSessionRepository repository;

    public SessionFamilyRevocationService(AuthSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(UUID familyId, String reason) {
        repository.revokeFamily(familyId, Instant.now(), reason);
    }
}
