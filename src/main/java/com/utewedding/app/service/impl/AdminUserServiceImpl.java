package com.utewedding.app.service.impl;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.EmailVerificationChallengeRepository;
import com.utewedding.app.repository.PasswordResetTokenRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.security.CustomUserDetailsService;
import com.utewedding.app.security.WebSessionRevocationService;
import com.utewedding.app.service.AdminUserService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.SessionService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AdminUserServiceImpl implements AdminUserService {
    private static final Set<UserRole> PRIVILEGED_ROLES = Set.of(
            UserRole.ADMIN, UserRole.MANAGER, UserRole.SUPER_ADMIN);

    private final UserRepository userRepository;
    private final AuditLogPort auditLog;
    private final SessionService sessionService;
    private final CustomUserDetailsService userDetailsService;
    private final WebSessionRevocationService webSessionRevocation;
    private final EmailVerificationChallengeRepository verificationChallengeRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PrivilegeGuard privilegeGuard = new PrivilegeGuard();

    @Autowired
    public AdminUserServiceImpl(UserRepository userRepository, AuditLogPort auditLog,
                                SessionService sessionService, CustomUserDetailsService userDetailsService,
                                WebSessionRevocationService webSessionRevocation,
                                EmailVerificationChallengeRepository verificationChallengeRepository,
                                PasswordResetTokenRepository passwordResetTokenRepository) {
        this.userRepository = userRepository;
        this.auditLog = auditLog;
        this.sessionService = sessionService;
        this.userDetailsService = userDetailsService;
        this.webSessionRevocation = webSessionRevocation;
        this.verificationChallengeRepository = verificationChallengeRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
    }

    /** Retained for focused service tests that exercise role/status changes. */
    public AdminUserServiceImpl(UserRepository userRepository, AuditLogPort auditLog,
                                SessionService sessionService, CustomUserDetailsService userDetailsService,
                                WebSessionRevocationService webSessionRevocation) {
        this(userRepository, auditLog, sessionService, userDetailsService, webSessionRevocation, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserEntity> list(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public UserEntity get(UUID id) {
        return userRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserEntity update(UUID actorId, UUID id, UserUpdateCommand command, RequestContext context) {
        userRepository.lockActiveSuperAdmins();
        UserEntity actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản thực hiện thao tác."));
        UserEntity user = get(id);
        privilegeGuard.assertCanMutate(actor, user, roleChangeRequested(user, command));
        UserRole requestedRole = requestedRole(command);

        UserRole oldRole = user.getRole();
        UserStatus oldStatus = user.getStatus();
        Instant oldDeletedAt = user.getDeletedAt();
        UserRole resultingRole = requestedRole == null ? user.getRole() : requestedRole;
        UserStatus resultingStatus = resultingStatus(user, command);
        if (actorId.equals(id) && isPrivileged(user)
                && (resultingStatus != UserStatus.ACTIVE
                || privilegeLevel(resultingRole) < privilegeLevel(user.getRole()))) {
            if (actor.getRole() == UserRole.SUPER_ADMIN) {
                throw new BusinessConflictException("Không thể tự hạ quyền hoặc vô hiệu hóa tài khoản quản trị.");
            }
            throw new AccessDeniedException("Không thể tự vô hiệu hóa tài khoản quản trị.");
        }
        ensureNotRemovingLastSuperAdmin(user, resultingRole, resultingStatus);

        if (requestedRole != null) user.setRole(requestedRole);
        if (command.status() != null) user.setStatus(command.status());
        if (Boolean.TRUE.equals(command.deleted()) || command.status() == UserStatus.DELETED) {
            user.setStatus(UserStatus.DELETED);
            user.setDeletedAt(Instant.now());
        } else if (Boolean.FALSE.equals(command.deleted()) && user.getStatus() == UserStatus.DELETED) {
            user.setStatus(UserStatus.ACTIVE);
            user.setDeletedAt(null);
        } else if (command.status() != null && command.status() != UserStatus.DELETED) {
            user.setDeletedAt(null);
        }
        UserEntity saved = userRepository.save(user);

        if (oldRole != saved.getRole()) {
            auditLog.record("USER_ROLES_CHANGED", actorId, "User", id,
                    Map.of("from", String.valueOf(oldRole), "to", String.valueOf(saved.getRole())), context);
        }
        if (oldStatus != saved.getStatus()) {
            String action = saved.getStatus() == UserStatus.DELETED
                    ? "USER_DELETED" : saved.getStatus() == UserStatus.ACTIVE && oldStatus == UserStatus.DELETED
                    ? "USER_RESTORED" : "USER_STATUS_CHANGED";
            auditLog.record(action, actorId, "User", id,
                    Map.of("from", String.valueOf(oldStatus), "to", String.valueOf(saved.getStatus())), context);
        }
        if (oldRole != saved.getRole() || oldStatus != saved.getStatus()
                || !java.util.Objects.equals(oldDeletedAt, saved.getDeletedAt())) {
            sessionService.revokeAllForUser(saved.getId(), "AUTHORIZATION_CHANGED");
            invalidateAuthorizationAfterCommit(saved.getId(), saved.getEmail());
        }
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProfileUpdateResult updateProfile(UUID actorId, UUID id, String displayName, String email,
                                             RequestContext context) {
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessConflictException("Họ tên không được để trống.");
        }
        if (displayName.trim().length() > 120) {
            throw new BusinessConflictException("Họ tên tối đa 120 ký tự.");
        }
        if (email == null || email.isBlank() || email.trim().length() > 320) {
            throw new BusinessConflictException("Email không hợp lệ.");
        }

        userRepository.lockActiveSuperAdmins();
        UserEntity actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản thực hiện thao tác."));
        UserEntity user = userRepository.findByIdForUpdate(id).orElseGet(() -> get(id));
        privilegeGuard.assertCanMutate(actor, user, false);
        if (user.getDeletedAt() != null || user.getStatus() == UserStatus.DELETED) {
            throw new BusinessConflictException("Không thể sửa hồ sơ đã xóa mềm. Hãy khôi phục tài khoản trước.");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String normalizedName = displayName.trim();
        String previousEmail = user.getEmail();
        boolean emailChanged = !previousEmail.equalsIgnoreCase(normalizedEmail);
        boolean displayNameChanged = !user.getDisplayName().equals(normalizedName);
        if (!emailChanged && !displayNameChanged) {
            return new ProfileUpdateResult(user, false);
        }
        if (emailChanged && userRepository.existsByEmailIgnoringCaseAndIdNotIncludingDeleted(normalizedEmail, id)) {
            throw new BusinessConflictException("Email này đã được sử dụng bởi tài khoản khác.");
        }

        user.setDisplayName(normalizedName);
        if (emailChanged) {
            user.setEmail(normalizedEmail);
            user.setEmailVerified(false);
            user.setEmailVerifiedAt(null);
            user.incrementPasswordVersion();
        }

        UserEntity saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException duplicateEmail) {
            throw new BusinessConflictException("Email này đã được sử dụng bởi tài khoản khác.");
        }

        if (emailChanged) {
            if (verificationChallengeRepository == null || passwordResetTokenRepository == null) {
                throw new IllegalStateException("Thiếu repository để thu hồi thông tin xác thực khi đổi email.");
            }
            Instant now = Instant.now();
            verificationChallengeRepository.consumeActiveForUser(id, now);
            passwordResetTokenRepository.invalidateActiveForUser(id, now);
            sessionService.revokeAllForUser(id, "EMAIL_CHANGED");
            invalidateAuthorizationAfterCommit(id, previousEmail);
        } else {
            evictUserDetailsAfterCommit(id, previousEmail);
        }
        auditLog.record("USER_PROFILE_UPDATED", actorId, "User", id,
                Map.of("displayNameChanged", displayNameChanged, "emailChanged", emailChanged), context);
        return new ProfileUpdateResult(saved, emailChanged);
    }

    private UserRole requestedRole(UserUpdateCommand command) {
        UserRole requested;
        if (command.roles() == null) {
            requested = command.role();
        } else {
            if (command.roles().size() != 1) {
                throw new BusinessConflictException("Mỗi tài khoản chỉ được có một role.");
            }
            UserRole roleFromSet = command.roles().iterator().next();
            if (roleFromSet == null) {
                throw new BusinessConflictException("Mỗi tài khoản chỉ được có một role.");
            }
            if (command.role() != null && command.role() != roleFromSet) {
                throw new BusinessConflictException("Role được yêu cầu không nhất quán.");
            }
            requested = roleFromSet;
        }
        return requested;
    }

    private boolean roleChangeRequested(UserEntity user, UserUpdateCommand command) {
        if (command == null) return false;
        if (command.role() != null && command.role() != user.getRole()) return true;
        return command.roles() != null
                && (command.roles().size() != 1 || !command.roles().contains(user.getRole()));
    }

    private UserStatus resultingStatus(UserEntity user, UserUpdateCommand command) {
        if (Boolean.TRUE.equals(command.deleted()) || command.status() == UserStatus.DELETED) {
            return UserStatus.DELETED;
        }
        if (Boolean.FALSE.equals(command.deleted()) && user.getStatus() == UserStatus.DELETED) {
            return UserStatus.ACTIVE;
        }
        return command.status() == null ? user.getStatus() : command.status();
    }

    private void ensureNotRemovingLastSuperAdmin(UserEntity target, UserRole resultingRole,
                                                  UserStatus resultingStatus) {
        if (target.getRole() != UserRole.SUPER_ADMIN || target.getStatus() != UserStatus.ACTIVE
                || target.getDeletedAt() != null
                || resultingRole == UserRole.SUPER_ADMIN && resultingStatus == UserStatus.ACTIVE) {
            return;
        }
        if (userRepository.countActiveSuperAdminsExcept(target.getId()) == 0) {
            throw new BusinessConflictException("Không thể vô hiệu hóa SUPER_ADMIN đang hoạt động cuối cùng.");
        }
    }

    private int privilegeLevel(UserRole role) {
        if (role == UserRole.SUPER_ADMIN) return 3;
        if (role == UserRole.ADMIN || role == UserRole.MANAGER) return 2;
        return 0;
    }

    private boolean isPrivileged(UserEntity user) {
        return user.getRole() != null && PRIVILEGED_ROLES.contains(user.getRole());
    }

    private void invalidateAuthorizationAfterCommit(UUID userId, String email) {
        evictUserDetailsAfterCommit(userId, email);
        webSessionRevocation.revokeAllAfterCommit(userId, email);
    }

    private void evictUserDetailsAfterCommit(UUID userId, String email) {
        Runnable evict = () -> userDetailsService.evict(userId, email);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }
}
