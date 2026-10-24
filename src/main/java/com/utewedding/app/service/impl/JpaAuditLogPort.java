package com.utewedding.app.service.impl;

import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.domain.entity.AuditLogEntity;
import com.utewedding.app.repository.AuditLogRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA audit adapter; raw IP addresses and secrets never reach the database. */
@Service
public class JpaAuditLogPort implements AuditLogPort {

    private final AuditLogRepository repository;
    private final String ipSalt;

    public JpaAuditLogPort(AuditLogRepository repository,
                           @Value("${security.audit.ip-salt}") String ipSalt) {
        this.repository = repository;
        if (ipSalt == null || ipSalt.isBlank()) {
            throw new IllegalStateException(
                    "security.audit.ip-salt must be configured with a non-blank AUDIT_IP_SALT");
        }
        this.ipSalt = ipSalt;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, UUID actorId, String targetType, UUID targetId,
                       Map<String, ?> metadata, RequestContext context) {
        record(action, actorId, targetType, targetId,
                targetId == null ? null : targetId.toString(), metadata, context);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTarget(String action, UUID actorId, String targetType, String targetReference,
                             Map<String, ?> metadata, RequestContext context) {
        record(action, actorId, targetType, parseUuid(targetReference), targetReference, metadata, context);
    }

    private void record(String action, UUID actorId, String targetType, UUID targetId,
                        String targetReference, Map<String, ?> metadata, RequestContext context) {
        AuditAction persistedAction = toPersistedAction(action);
        repository.save(new AuditLogEntity(persistedAction, actorId,
                resolveActorRole(actorId, persistedAction, context), targetType, targetId, limit(targetReference, 80),
                safeMetadata(metadata), hashIp(context == null ? null : context.ipAddress()),
                limit(context == null ? null : context.userAgent(), 512),
                limit(context == null ? null : context.requestId(), 64)));
    }

    private String resolveActorRole(UUID actorId, AuditAction action, RequestContext context) {
        if (context != null && context.actorRole() != null && !context.actorRole().isBlank()) {
            String role = context.actorRole().trim().toUpperCase(Locale.ROOT);
            return limit(role.startsWith("ROLE_") ? role.substring(5) : role, 30);
        }
        if (actorId == null) {
            return action == AuditAction.USER_LOGIN_FAILED ? "ANONYMOUS" : "SYSTEM";
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || !actorId.equals(principal.getId())) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority != null && authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .sorted((left, right) -> Integer.compare(rolePriority(left), rolePriority(right)))
                .findFirst()
                .map(role -> limit(role, 30))
                .orElse(null);
    }

    private int rolePriority(String role) {
        return switch (role) {
            case "SUPER_ADMIN" -> 0;
            case "ADMIN" -> 1;
            case "MANAGER" -> 2;
            case "VENDOR" -> 3;
            case "SHIPPER" -> 4;
            case "USER", "CUSTOMER" -> 5;
            default -> 6;
        };
    }

    private UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private AuditAction toPersistedAction(String raw) {
        if (raw == null || raw.isBlank()) return AuditAction.AUDIT_ACTION_UNMAPPED;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        // These service-level names predate the PostgreSQL enum. Preserve the
        // event without attempting to write a value the database cannot accept.
        if ("USER_TOKEN_REFRESHED".equals(value)) return AuditAction.SESSION_ROTATED;
        if ("USER_LOGGED_OUT".equals(value)) return AuditAction.USER_LOGOUT_ALL;
        if ("WEDDING_TIMELINE_UPDATED".equals(value)) return AuditAction.WEDDING_UPDATED;
        if ("CHECKOUT_GROUP_CREATED".equals(value)) return AuditAction.ORDER_CREATED;
        if ("CHECKOUT_GROUP_CLOSED".equals(value)) return AuditAction.ORDER_CANCELLED;
        if ("CHECKOUT_GROUP_LATE_CAPTURE".equals(value)
                || "CHECKOUT_GROUP_PAYMENT_CAPTURED".equals(value)
                || "CHECKOUT_GROUP_ZERO_DUE".equals(value)) return AuditAction.PAYMENT_STATUS_CHANGED;
        try {
            return AuditAction.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return AuditAction.AUDIT_ACTION_UNMAPPED;
        }
    }

    private Map<String, Object> safeMetadata(Map<String, ?> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metadata == null) return result;
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank()) return;
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (normalized.contains("token") || normalized.contains("password")
                    || normalized.contains("secret") || normalized.contains("authorization")
                    || normalized.contains("session") || normalized.contains("cookie")
                    || normalized.contains("credential") || normalized.contains("signature")
                    || normalized.contains("transactionid") || normalized.contains("email")
                    || normalized.contains("phone") || normalized.contains("accountnumber")
                    || normalized.contains("ipaddress") || normalized.equals("ip")
                    || normalized.contains("clientip") || normalized.contains("remoteip")
                    || normalized.contains("rawip")) {
                return;
            }
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof Enum<?> || value instanceof UUID) {
                result.put(limit(key, 80), value == null ? null : limit(String.valueOf(value), 1024));
            }
        });
        return result;
    }

    private String hashIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((ipSalt + ":" + ip).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
