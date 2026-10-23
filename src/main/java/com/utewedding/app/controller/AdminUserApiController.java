package com.utewedding.app.controller;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.service.AdminUserService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import com.utewedding.app.service.PageableSupport;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminUserApiController {
    private final AdminUserService adminUserService;
    public AdminUserApiController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }
    @GetMapping public Page<AdminUserResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminUserService.list(normalize(pageable)).map(AdminUserApiController::toResponse);
    }
    @GetMapping("/{id}") public AdminUserResponse get(@PathVariable UUID id) {
        return toResponse(adminUserService.get(id));
    }
    @PatchMapping("/{id}") public AdminUserResponse update(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UserUpdate request, HttpServletRequest httpRequest) {
        return toResponse(adminUserService.update(actor.getId(), id,
                new AdminUserService.UserUpdateCommand(request.role(), request.roles(), request.status(), request.deleted()),
                new RequestContext(httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                        httpRequest.getHeader("X-Request-Id"))));
    }
    private static AdminUserResponse toResponse(UserEntity user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                Set.of(user.getRole()), user.getStatus(), user.isEmailVerified(), user.getCreatedAt(), user.getDeletedAt());
    }

    private Pageable normalize(Pageable pageable) {
        return PageableSupport.normalize(pageable, "createdAt",
                Set.of("createdAt", "updatedAt", "email", "displayName", "status"));
    }
    /** Nullable so omitting deleted does not accidentally restore a soft-deleted account. */
    /** The roles property is retained for API compatibility and accepts one canonical role only. */
    public record UserUpdate(UserRole role, Set<UserRole> roles, UserStatus status, Boolean deleted) { }
    /** The roles response property is a compatibility alias containing only role. */
    public record AdminUserResponse(UUID id, String email, String displayName, UserRole role,
                                    Set<UserRole> roles,
                                    UserStatus status, boolean emailVerified, java.time.Instant createdAt,
                                    java.time.Instant deletedAt) { }
}
