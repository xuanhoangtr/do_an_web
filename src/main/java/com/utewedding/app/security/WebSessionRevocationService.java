package com.utewedding.app.security;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Expires servlet sessions after the surrounding transaction commits. */
@Service
public class WebSessionRevocationService {

    private final SessionRegistry sessionRegistry;
    private final WebSessionMetadataService metadataService;

    public WebSessionRevocationService(SessionRegistry sessionRegistry) {
        this(sessionRegistry, null);
    }

    @Autowired
    public WebSessionRevocationService(SessionRegistry sessionRegistry, WebSessionMetadataService metadataService) {
        this.sessionRegistry = sessionRegistry;
        this.metadataService = metadataService;
    }

    public void revokeAllAfterCommit(UUID userId) {
        revokeAllAfterCommit(userId, null);
    }

    public void revokeAllAfterCommit(UUID userId, String principalName) {
        runAfterCommit(() -> expire(userId, principalName, null));
    }

    public void revokeAllExceptAfterCommit(UUID userId, String retainedSessionId) {
        revokeAllExceptAfterCommit(userId, null, retainedSessionId);
    }

    public void revokeAllExceptAfterCommit(UUID userId, String principalName, String retainedSessionId) {
        String retained = retainedSessionId == null || retainedSessionId.isBlank() ? null : retainedSessionId;
        runAfterCommit(() -> expire(userId, principalName, retained));
    }

    public void revokeSessionAfterCommit(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        runAfterCommit(() -> {
            expireSession(sessionId);
            if (metadataService != null) metadataService.revokeSession(sessionId);
        });
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private void expire(UUID userId, String principalName, String retainedSessionId) {
        if (userId == null) return;
        if (sessionRegistry != null) {
            List<Object> principals = sessionRegistry.getAllPrincipals();
            if (principals != null) {
                for (Object principal : principals) {
                    if (!(principal instanceof UserPrincipal userPrincipal) || !userId.equals(userPrincipal.getId())) {
                        continue;
                    }
                    List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
                    if (sessions == null) continue;
                    for (SessionInformation session : sessions) {
                        if (session == null) continue;
                        if (retainedSessionId != null && retainedSessionId.equals(session.getSessionId())) continue;
                        session.expireNow();
                    }
                }
            }
        }
        if (metadataService != null) {
            metadataService.revokeUserSessions(userId, principalName, retainedSessionId);
        }
    }

    private void expireSession(String sessionId) {
        if (sessionRegistry == null) return;
        List<Object> principals = sessionRegistry.getAllPrincipals();
        if (principals == null) return;
        for (Object principal : principals) {
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            if (sessions == null) continue;
            for (SessionInformation session : sessions) {
                if (session != null && sessionId.equals(session.getSessionId())) session.expireNow();
            }
        }
    }
}
