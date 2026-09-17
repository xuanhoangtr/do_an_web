package com.utewedding.app.service.impl;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.entity.UserEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Shared service-layer authorization policy for mutations involving another account. */
@Service
public class PrivilegeGuard {
    public void assertCanMutate(UserEntity actor, UserEntity target, boolean changingRole) {
        if (actor == null || target == null) {
            throw new AccessDeniedException("Không xác định được tài khoản thực hiện thao tác.");
        }
        if (changingRole && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ SUPER_ADMIN được đổi role.");
        }
        if (actor.getRole() != UserRole.SUPER_ADMIN
                && privilegeLevel(actor.getRole()) < privilegeLevel(target.getRole())) {
            throw new AccessDeniedException("Không được tác động tài khoản có quyền cao hơn.");
        }
        if ((target.getRole() == UserRole.ADMIN || target.getRole() == UserRole.SUPER_ADMIN)
                && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ SUPER_ADMIN được tác động tài khoản ADMIN hoặc SUPER_ADMIN.");
        }
    }

    private int privilegeLevel(UserRole role) {
        if (role == UserRole.SUPER_ADMIN) return 3;
        if (role == UserRole.ADMIN || role == UserRole.MANAGER) return 2;
        return 0;
    }
}
