package com.utewedding.app.security;

import com.utewedding.app.domain.entity.UserEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

/** Spring Security principal backed by the current database row. */
public final class UserPrincipal implements UserDetails {

    // Preserve sessions serialized by the previous release before vendorAccount was added.
    private static final long serialVersionUID = -542246246942082849L;

    private final UUID id;
    private final String email;
    private final String displayName;
    private final String passwordHash;
    private final AppRole role;
    private final boolean vendorAccount;
    private final Instant passwordChangedAt;
    private final int passwordVersion;
    private final String sessionId;
    private final boolean emailVerified;

    private UserPrincipal(
            UUID id,
            String email,
            String displayName,
            String passwordHash,
            AppRole role,
            boolean vendorAccount,
            Instant passwordChangedAt,
            int passwordVersion,
            String sessionId,
            boolean emailVerified) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.vendorAccount = vendorAccount;
        this.passwordChangedAt = passwordChangedAt;
        this.passwordVersion = passwordVersion;
        this.sessionId = sessionId;
        this.emailVerified = emailVerified;
    }

    /** Builds authorities only from the canonical users.role column. */
    public static UserPrincipal from(UserEntity user) {
        AppRole primaryRole = AppRole.from(user.getRole().name());
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPasswordHash(),
                primaryRole,
                user.getRole() == com.utewedding.app.domain.UserRole.VENDOR,
                user.getPasswordChangedAt(),
                user.getPasswordVersion(),
                null,
                user.isEmailVerified());
    }

    public UserPrincipal withSessionId(String value) {
        return new UserPrincipal(id, email, displayName, passwordHash, role, vendorAccount, passwordChangedAt,
                passwordVersion, value, emailVerified);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AppRole getRole() {
        return role;
    }

    public boolean isVendorAccount() {
        return vendorAccount;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public int getPasswordVersion() {
        return passwordVersion;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return emailVerified;
    }
}
