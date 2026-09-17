package com.utewedding.app.service.impl;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.repository.AuditLogRepository;
import com.utewedding.app.repository.UserRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OperationsQueryService {
    private static final Set<UserRole> STAFF_ROLES = Set.of(
            UserRole.MANAGER, UserRole.ADMIN, UserRole.SUPER_ADMIN);

    private final UserRepository users;
    private final AuditLogRepository audits;

    public OperationsQueryService(UserRepository users, AuditLogRepository audits) {
        this.users = users;
        this.audits = audits;
    }

    public void requireStaff(UUID actor) {
        var user = users.findById(actor)
                .orElseThrow(() -> new AccessDeniedException("Không có quyền vận hành."));
        if (user.getStatus() != UserStatus.ACTIVE || !STAFF_ROLES.contains(user.getRole())) {
            throw new AccessDeniedException("Không có quyền vận hành.");
        }
    }

    public java.util.List<AuditView> auditTrail(UUID actor, String type, UUID id) {
        requireStaff(actor);
        return audits.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(type, id, PageRequest.of(0, 50))
                .stream()
                .map(entry -> new AuditView(entry.getAction().name(), entry.getActorId(), entry.getCreatedAt()))
                .toList();
    }

    public record AuditView(String action, UUID actorId, Instant createdAt) { }
}
