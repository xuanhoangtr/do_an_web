package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.PasswordResetTokenEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.ResetPasswordRequest;
import com.utewedding.app.dto.response.MessageResponse;
import com.utewedding.app.event.PasswordResetRequestedEvent;
import com.utewedding.app.exception.InvalidOtpException;
import com.utewedding.app.exception.UnauthorizedException;
import com.utewedding.app.repository.PasswordResetTokenRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.security.WebSessionRevocationService;
import com.utewedding.app.service.PasswordResetService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.PasswordPolicy;
import com.utewedding.app.service.port.PasswordHashService;
import com.utewedding.app.service.port.SessionService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;
import com.utewedding.app.security.CustomUserDetailsService;
import com.utewedding.app.service.port.SecureHash;

@Service
public class JpaPasswordResetService implements PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordHashService passwordHashService;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomUserDetailsService userDetailsService;
    private final WebSessionRevocationService webSessionRevocation;
    private final Duration tokenTtl;
    private final Duration otpTtl;
    private final String resetUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public JpaPasswordResetService(UserRepository userRepository,
                                   PasswordResetTokenRepository tokenRepository,
                                   PasswordHashService passwordHashService,
                                   SessionService sessionService,
                                   ApplicationEventPublisher eventPublisher,
                                   CustomUserDetailsService userDetailsService,
                                   WebSessionRevocationService webSessionRevocation,
                                   @Value("${security.password-reset.ttl:1h}") Duration tokenTtl,
                                   @Value("${security.password-reset.otp-ttl:10m}") Duration otpTtl,
                                   @Value("${app.url:http://localhost:8080}") String appUrl) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordHashService = passwordHashService;
        this.sessionService = sessionService;
        this.eventPublisher = eventPublisher;
        this.userDetailsService = userDetailsService;
        this.webSessionRevocation = webSessionRevocation;
        this.tokenTtl = tokenTtl;
        this.otpTtl = otpTtl;
        this.resetUrl = appUrl.replaceAll("/+$", "") + "/reset-password";
    }

    @Override
    @Async("passwordResetExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void request(String email, String requestIp) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        UserEntity user = userRepository.findByEmail(normalized).orElse(null);
        if (user == null) return;
        user = userRepository.findByIdForUpdate(user.getId()).orElse(null);
        if (user == null) return;
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant now = Instant.now();
        tokenRepository.invalidateActiveForUser(user.getId(), now);
        PasswordResetTokenEntity entity = new PasswordResetTokenEntity(user.getId(), SecureHash.sha256(token),
                now.plus(tokenTtl), limit(requestIp, 64));
        entity.setOtp(SecureHash.sha256(otp), now.plus(otpTtl));
        tokenRepository.save(entity);
        eventPublisher.publishEvent(new PasswordResetRequestedEvent(
                user.getEmail(), otp, resetUrl + "?token=" + token));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = InvalidOtpException.class)
    public MessageResponse reset(ResetPasswordRequest request, RequestContext context) {
        if (request == null) throw new IllegalArgumentException("Yêu cầu đặt lại mật khẩu không hợp lệ.");
        PasswordPolicy.requireValid(request.newPassword());
        Instant now = Instant.now();
        PasswordResetTokenEntity token;
        UserEntity user;
        if (request.token() != null && !request.token().isBlank()) {
            String tokenHash = SecureHash.sha256(request.token().trim());
            UUID userId = tokenRepository.findUserIdByTokenHash(tokenHash)
                    .orElseThrow(() -> new UnauthorizedException("Liên kết khôi phục không hợp lệ."));
            user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new UnauthorizedException("Tài khoản không còn khả dụng."));
            token = tokenRepository.findByTokenHashForUpdate(tokenHash)
                    .orElseThrow(() -> new UnauthorizedException("Liên kết khôi phục không hợp lệ."));
            if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
                throw new UnauthorizedException("Liên kết khôi phục đã hết hạn.");
            }
        } else {
            String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
            user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new UnauthorizedException("OTP khôi phục không hợp lệ."));
            user = userRepository.findByIdForUpdate(user.getId())
                    .orElseThrow(() -> new UnauthorizedException("OTP khôi phục không hợp lệ."));
            token = tokenRepository.findLatestActiveForUser(user.getId())
                    .orElseThrow(() -> new UnauthorizedException("OTP khôi phục không hợp lệ."));
            if (token.getOtpHash() == null || token.getOtpExpiresAt() == null
                    || !token.getOtpExpiresAt().isAfter(now) || token.getOtpAttempts() >= 5) {
                throw new UnauthorizedException("OTP khôi phục đã hết hạn hoặc bị khóa.");
            }
            if (!java.security.MessageDigest.isEqual(token.getOtpHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    SecureHash.sha256(request.otp() == null ? "" : request.otp().trim())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                token.incrementOtpAttempts();
                tokenRepository.save(token);
                throw new InvalidOtpException("OTP khôi phục không hợp lệ.");
            }
        }
        user.setPasswordHash(passwordHashService.hash(request.newPassword()));
        user.setPasswordChangedAt(Instant.now());
        user.incrementPasswordVersion();
        userRepository.save(user);
        // The principal cache must not serve the old password version after a reset.
        // This also makes the next JWT contain the current revocation version.
        userDetailsService.evict(user.getId(), user.getEmail());
        token.setUsedAt(now);
        tokenRepository.save(token);
        sessionService.revokeAllForUser(user.getId(), "PASSWORD_RESET");
        webSessionRevocation.revokeAllAfterCommit(user.getId(), user.getEmail());
        return new MessageResponse("Đặt lại mật khẩu thành công.");
    }

    private static String limit(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
