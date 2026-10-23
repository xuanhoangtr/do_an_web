package com.utewedding.app.service;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.UserEntity;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminUserService {
    Page<UserEntity> list(Pageable pageable);
    UserEntity get(UUID id);
    UserEntity update(UUID actorId, UUID id, UserUpdateCommand command, RequestContext context);
    ProfileUpdateResult updateProfile(UUID actorId, UUID id, String displayName, String email,
                                      RequestContext context);

    record UserUpdateCommand(UserRole role, Set<UserRole> roles, UserStatus status, Boolean deleted) { }
    record ProfileUpdateResult(UserEntity user, boolean emailChanged) { }
}
