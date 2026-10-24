package com.utewedding.app.service.web;

import com.utewedding.app.domain.AuditAction;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogRow(
        Instant createdAt,
        String timestamp,
        String actor,
        UUID actorId,
        String actorRole,
        AuditAction action,
        String actionLabel,
        String targetType,
        String targetReference,
        String summary,
        Map<String, String> safeMetadata,
        String targetUrl) {
}
