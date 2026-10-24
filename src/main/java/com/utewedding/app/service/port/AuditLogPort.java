package com.utewedding.app.service.port;

import com.utewedding.app.service.RequestContext;
import java.util.Map;
import java.util.UUID;

public interface AuditLogPort {
    void record(String action, UUID actorId, String targetType, UUID targetId,
                Map<String, ?> metadata, RequestContext context);

    void recordTarget(String action, UUID actorId, String targetType, String targetReference,
                      Map<String, ?> metadata, RequestContext context);
}
