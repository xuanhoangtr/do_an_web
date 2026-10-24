package com.utewedding.app.service.impl;

import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.util.Map;
import java.util.UUID;

/** Safe default for the migration slice; replace with the JPA audit adapter. */
public class NoOpAuditLogPort implements AuditLogPort {
    @Override
    public void record(String action, UUID actorId, String targetType, UUID targetId,
                       Map<String, ?> metadata, RequestContext context) {
        // Intentionally empty. The service remains decoupled from audit persistence.
    }

    @Override
    public void recordTarget(String action, UUID actorId, String targetType, String targetReference,
                             Map<String, ?> metadata, RequestContext context) {
        // Intentionally empty. The service remains decoupled from audit persistence.
    }
}
