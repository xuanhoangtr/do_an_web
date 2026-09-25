package com.utewedding.app.service.impl;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.ChangePasswordRequest;
import com.utewedding.app.dto.request.LoginRequest;
import com.utewedding.app.dto.request.RegisterRequest;
import com.utewedding.app.dto.response.AuthResponse;
import com.utewedding.app.dto.response.MessageResponse;
import com.utewedding.app.dto.response.UserResponse;
import com.utewedding.app.exception.DuplicateRegistrationException;
import com.utewedding.app.exception.UnauthorizedException;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.AuthService;
import com.utewedding.app.service.EmailVerificationService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.PasswordHashService;
import com.utewedding.app.service.port.SessionService;
import com.utewedding.app.service.port.SessionTokens;
import com.utewedding.app.service.port.TokenService;
import com.utewedding.app.security.CustomUserDetailsService;
import com.utewedding.app.security.WebSessionRevocationService;
import com.utewedding.app.service.port.SessionService.SessionRotation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String PASSWORD_CHANGED_MESSAGE = "Đổi mật khẩu thành công.";
    // Same Argon2id parameters as the production encoder. This is only used
    // when the account lookup misses, so credential timing does not disclose
    // whether an email exists.
    private static final String DUMMY_ARGON2_HASH =
            "$argon2id$v=19$m=19456,t=2,p=1$d+aYfuG8uLx8Cr8L1lGZzw$Lw04Sbe3A+MQUoBxeisUFPPQpWuIfzg+R5ol5B5MdZI";
    private final UserRepository userRepository;
    private final PasswordHashService passwordHashService;
    private final TokenService tokenService;
    private final SessionService sessionService;
    private final AuditLogPort auditLog;
    private final CustomUserDetailsService userDetailsService;
    private final EmailVerificationService emailVerificationService;
    private final WebSessionRevocationService webSessionRevocation;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordHashService passwordHashService,
                           TokenService tokenService,
                           SessionService sessionService,
                           AuditLogPort auditLog,
                           CustomUserDetailsService userDetailsService,
                           EmailVerificationService emailVerificationService,
                           WebSessionRevocationService webSessionRevocation) {
        this.userRepository = userRepository;
        this.passwordHashService = passwordHashService;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.auditLog = auditLog;
        this.userDetailsService = userDetailsService;
        this.emailVerificationService = emailVerificationService;
        this.webSessionRevocation = webSessionRevocation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageResponse register(RegisterRequest request, RequestContext context) {
        String email = normaliseEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateRegistrationException();
        }
        String username = normaliseUsername(request.username());
        if (username != null && userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            throw new com.utewedding.app.exception.DuplicateUsernameException();
        }
        String passwordHash = passwordHashService.hash(request.password());

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setUsername(username);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordHash);
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException duplicateEmail) {
            throw new DuplicateRegistrationException();
        }

        auditLog.record("USER_REGISTERED", user.getId(), "User", user.getId(), Map.of(), context);
        emailVerificationService.issue(user.getId(), user.getEmail(), context);
        return new MessageResponse(REGISTRATION_ACCEPTED_MESSAGE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthResponse login(LoginRequest request, RequestContext context) {
        String email = normaliseEmail(request.email());
        UserEntity user = userRepository.findByEmail(email).orElse(null);
        boolean valid = user != null
                ? passwordHashService.matches(request.password(), user.getPasswordHash())
                : passwordHashService.matches(request.password(),
                        DUMMY_ARGON2_HASH);
        if (user == null || !valid) {
            auditLog.record("USER_LOGIN_FAILED", user == null ? null : user.getId(), "User",
                    user == null ? null : user.getId(), Map.of("reason", "INVALID_CREDENTIALS"), context);
            throw new UnauthorizedException("Email hoặc mật khẩu không đúng.");
        }
        if (!user.isEmailVerified()) {
            throw new com.utewedding.app.exception.EmailNotVerifiedException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("Tài khoản đã bị khoá.");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        auditLog.record("USER_LOGIN_SUCCEEDED", user.getId(), "User", user.getId(), Map.of(), context);
        return issueTokens(user, context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageResponse changePassword(UUID userId, ChangePasswordRequest request,
                                          RequestContext context, String currentWebSessionId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Tài khoản không tồn tại hoặc đã bị khoá."));
        if (!passwordHashService.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Mật khẩu hiện tại không đúng.");
        }
        user.setPasswordHash(passwordHashService.hash(request.newPassword()));
        user.setPasswordChangedAt(Instant.now());
        user.incrementPasswordVersion();
        userRepository.save(user);
        userDetailsService.evict(user.getId(), user.getEmail());
        UUID currentApiSession = currentApiSessionId();
        if (currentApiSession == null) sessionService.revokeAllForUser(userId, "PASSWORD_CHANGED");
        else sessionService.revokeAllExcept(userId, currentApiSession, "PASSWORD_CHANGED");
        webSessionRevocation.revokeAllExceptAfterCommit(userId, user.getEmail(), currentWebSessionId);
        auditLog.record("USER_PASSWORD_CHANGED", userId, "User", userId, Map.of(), context);
        return new MessageResponse(PASSWORD_CHANGED_MESSAGE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthResponse refresh(String refreshToken, RequestContext context) {
        SessionRotation rotation = sessionService.rotateSession(refreshToken, context);
        UserEntity user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException("Tài khoản không còn khả dụng."));
        auditLog.record("USER_TOKEN_REFRESHED", user.getId(), "User", user.getId(), Map.of(), context);
        return issueTokens(user, rotation.tokens());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageResponse logout(String refreshToken, UUID userId, RequestContext context) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessionService.revokeRefreshToken(refreshToken, "LOGOUT");
        } else if (userId != null) {
            sessionService.revokeAllForUser(userId, "LOGOUT");
        }
        if (userId != null) {
            auditLog.record("USER_LOGGED_OUT", userId, "User", userId, Map.of(), context);
        }
        return new MessageResponse("Đăng xuất thành công.");
    }

    private AuthResponse issueTokens(UserEntity user, RequestContext context) {
        SessionTokens session = sessionService.createSession(user.getId(), context);
        return issueTokens(user, session);
    }

    private AuthResponse issueTokens(UserEntity user, SessionTokens session) {
        String accessToken = tokenService.issueAccessToken(
                user.getId(), user.getEmail(), user.getRole(), session.sessionId(),
                user.getPasswordVersion());
        return new AuthResponse(accessToken, tokenService.accessTokenTtlSeconds(), toUserResponse(user),
                session.refreshToken(), session.refreshTokenExpiresAt());
    }

    private UUID currentApiSessionId() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof com.utewedding.app.security.UserPrincipal principal)
                || principal.getSessionId() == null || principal.getSessionId().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(principal.getSessionId());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private UserResponse toUserResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.isEmailVerified(), user.getCreatedAt());
    }

    private String normaliseEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normaliseUsername(String username) {
        return username == null || username.isBlank()
                ? null : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
