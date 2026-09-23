package com.utewedding.app.security;

import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/** Rebuilds the principal from current database state for every authentication. */
@Service
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final UserRepository userRepository;
    private final UserPrincipalCache principalCache;
    private final String testLoginDomain;

    public CustomUserDetailsService(UserRepository userRepository, UserPrincipalCache principalCache) {
        this(userRepository, principalCache, "");
    }

    @Autowired
    public CustomUserDetailsService(
            UserRepository userRepository,
            UserPrincipalCache principalCache,
            @Value("${app.auth.test-login-domain:}") String testLoginDomain) {
        this.userRepository = userRepository;
        this.principalCache = principalCache;
        this.testLoginDomain = testLoginDomain == null
                ? "" : testLoginDomain.trim().toLowerCase(Locale.ROOT);
    }

    /** Form login supplies the username field; the migrated API uses email. */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String login = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String email = userRepository.findByUsernameIgnoreCase(login)
                .map(UserEntity::getEmail)
                .orElseGet(() -> normalizeLogin(login));
        return principalCache.getByEmail(email, key -> userRepository.findByEmail(key)
                .map(user -> toPrincipal(user, false))
                .orElseThrow(this::notFound));
    }

    private String normalizeLogin(String username) {
        String login = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!testLoginDomain.isBlank() && !login.isBlank() && !login.contains("@")
                && login.matches("[a-z0-9._-]+")) {
            return login + ".test@" + testLoginDomain;
        }
        return login;
    }

    /** JWT {@code sub} is the immutable user UUID, never a role claim. */
    public UserPrincipal loadUserById(String rawId) throws UsernameNotFoundException {
        final UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            throw notFound();
        }

        return principalCache.getById(id, key -> userRepository.findById(key)
                .map(user -> toPrincipal(user, true))
                .orElseThrow(this::notFound));
    }

    /** Reads current account state without the principal cache for long-lived browser sockets. */
    public boolean isActiveAccount(UUID id) {
        if (id == null) return false;
        return userRepository.findById(id)
                .filter(user -> user.getStatus() == com.utewedding.app.domain.UserStatus.ACTIVE)
                .filter(user -> user.getDeletedAt() == null && user.isEmailVerified())
                .isPresent();
    }

    /** Rehashes a legacy BCrypt password after successful form authentication. */
    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails current, String newPassword) {
        String email = current == null || current.getUsername() == null
                ? "" : current.getUsername().trim().toLowerCase(Locale.ROOT);
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(this::notFound);
        user.setPasswordHash(newPassword);
        user.setPasswordChangedAt(java.time.Instant.now());
        userRepository.save(user);
        principalCache.invalidate(user.getId(), user.getEmail());
        return UserPrincipal.from(user);
    }

    public void evict(UUID id, String email) {
        principalCache.invalidate(id, email);
    }

    private UserPrincipal toPrincipal(UserEntity user, boolean exposeEmailVerificationFailure) {
        if (user.getStatus() != com.utewedding.app.domain.UserStatus.ACTIVE
                || user.getDeletedAt() != null) {
            // Deliberately hide suspended/deleted account state from callers.
            throw notFound();
        }
        if (!user.isEmailVerified()) {
            if (exposeEmailVerificationFailure) {
                throw new com.utewedding.app.exception.EmailNotVerifiedException();
            }
            throw notFound();
        }

        try {
            return UserPrincipal.from(user);
        } catch (IllegalArgumentException exception) {
            // An unknown role must fail closed rather than become an authority.
            throw notFound();
        }
    }

    private UsernameNotFoundException notFound() {
        return new UsernameNotFoundException("Invalid user credentials");
    }
}
