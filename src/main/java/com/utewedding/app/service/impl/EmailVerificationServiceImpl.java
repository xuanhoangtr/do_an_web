package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.EmailVerificationChallengeEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.ResendVerificationRequest;
import com.utewedding.app.dto.request.VerifyEmailRequest;
import com.utewedding.app.dto.response.MessageResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.InvalidOtpException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.exception.UnauthorizedException;
import com.utewedding.app.repository.EmailVerificationChallengeRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.EmailVerificationService;
import com.utewedding.app.service.EmailVerificationRateLimitService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration DEFAULT_RESEND_COOLDOWN = Duration.ofSeconds(60);
    private final EmailVerificationChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final AuditLogPort auditLog;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;
    private final EmailVerificationRateLimitService rateLimitService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration challengeTtl;
    private final Duration resendCooldown;
    private final Duration rateLimitWindow;

    public EmailVerificationServiceImpl(EmailVerificationChallengeRepository challengeRepository,
                                        UserRepository userRepository,
                                        AuditLogPort auditLog,
                                        ApplicationEventPublisher eventPublisher,
                                        OutboxService outbox,
                                        ObjectMapper objectMapper,
                                        EmailVerificationRateLimitService rateLimitService,
                                        @Value("${security.email-verification.ttl:10m}") Duration challengeTtl,
                                        @Value("${security.email-verification.resend-cooldown:60s}") Duration resendCooldown,
                                        @Value("${security.email-verification.rate-limit-window:1h}") Duration rateLimitWindow) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.auditLog = auditLog;
        this.eventPublisher = eventPublisher;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        if (challengeTtl.isNegative() || challengeTtl.isZero()) {
            throw new IllegalArgumentException("Email verification TTL must be positive");
        }
        this.challengeTtl = challengeTtl;
        this.resendCooldown = resendCooldown.isNegative() || resendCooldown.isZero()
                ? DEFAULT_RESEND_COOLDOWN : resendCooldown;
        if (rateLimitWindow.isNegative() || rateLimitWindow.isZero()) {
            throw new IllegalArgumentException("Email verification rate-limit window must be positive");
        }
        this.rateLimitWindow = rateLimitWindow;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void issue(UUID userId, String email, RequestContext context) {
        String normalized = normalize(email);
        Instant now = Instant.now();
        EmailVerificationChallengeEntity current = challengeRepository
                .findActiveForUserForUpdate(userId).orElse(null);
        if (current != null && current.getLastSentAt() != null
                && current.getLastSentAt().plus(resendCooldown).isAfter(now)) {
            throw new BusinessConflictException("Vui lòng chờ trước khi gửi lại mã xác thực.");
        }
        rateLimitService.consume(normalized, context == null ? null : context.ipAddress());
        String otp = otp();
        if (current == null) {
            current = new EmailVerificationChallengeEntity(userId, normalized, hash(otp),
                    null, now.plus(challengeTtl), now, limitIp(context));
        } else {
            boolean resetWindow = !current.getWindowStartedAt().plus(rateLimitWindow).isAfter(now);
            current.rotate(hash(otp), null, now.plus(challengeTtl), now,
                    limitIp(context), resetWindow);
        }
        challengeRepository.save(current);
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "recipient", normalized, "otp", otp));
            outbox.enqueue("EMAIL_VERIFICATION", "EmailVerificationChallenge", current.getId(),
                "email-verification:" + current.getId() + ":" + current.getSentCount(), payload, context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể tạo email outbox payload.", exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = InvalidOtpException.class)
    public MessageResponse verifyOtp(VerifyEmailRequest request, RequestContext context) {
        String email = normalize(request.email());
        EmailVerificationChallengeEntity challenge = challengeRepository
                .findActiveForEmailForUpdate(email)
                .orElseThrow(() -> new UnauthorizedException("Mã xác thực không hợp lệ hoặc đã hết hạn."));
        return verify(challenge, hash(request.otp()), context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = InvalidOtpException.class)
    public MessageResponse verifyLink(String token, RequestContext context) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Liên kết xác thực không hợp lệ.");
        }
        EmailVerificationChallengeEntity challenge = challengeRepository
                .findByLinkTokenHashAndConsumedAtIsNull(hash(token.trim()))
                .orElseThrow(() -> new UnauthorizedException("Liên kết xác thực không hợp lệ hoặc đã hết hạn."));
        return verify(challenge, hash(token.trim()), context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageResponse resend(ResendVerificationRequest request, RequestContext context) {
        String email = normalize(request.email());
        UserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.isEmailVerified()) {
            return new MessageResponse("Nếu email cần xác thực, mã mới sẽ được gửi.");
        }
        issue(user.getId(), user.getEmail(), context);
        return new MessageResponse("Nếu email cần xác thực, mã mới sẽ được gửi.");
    }

    private MessageResponse verify(EmailVerificationChallengeEntity challenge, String suppliedHash,
                                   RequestContext context) {
        Instant now = Instant.now();
        if (!challenge.isActive(now)) {
            throw new UnauthorizedException("Mã xác thực không hợp lệ hoặc đã hết hạn.");
        }
        if (challenge.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new UnauthorizedException("Mã xác thực đã bị khóa do nhập sai quá nhiều lần.");
        }
        boolean otpMatches = MessageDigest.isEqual(challenge.getOtpHash().getBytes(StandardCharsets.UTF_8),
                suppliedHash.getBytes(StandardCharsets.UTF_8));
        String legacyLinkTokenHash = challenge.getLinkTokenHash();
        boolean legacyLinkMatches = legacyLinkTokenHash != null
                && MessageDigest.isEqual(legacyLinkTokenHash.getBytes(StandardCharsets.UTF_8),
                suppliedHash.getBytes(StandardCharsets.UTF_8));
        if (!otpMatches && !legacyLinkMatches) {
            challenge.incrementAttemptCount();
            challengeRepository.save(challenge);
            throw new InvalidOtpException("Mã xác thực không hợp lệ.");
        }
        UserEntity user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(now);
        userRepository.save(user);
        challenge.setConsumedAt(now);
        challengeRepository.save(challenge);
        auditLog.record("USER_EMAIL_VERIFIED", user.getId(), "User", user.getId(),
                java.util.Map.of(), context);
        return new MessageResponse("Email đã được xác thực. Bạn có thể đăng nhập.");
    }

    private String otp() { return String.format("%06d", secureRandom.nextInt(1_000_000)); }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String limitIp(RequestContext context) {
        if (context == null || context.ipAddress() == null) return null;
        String value = context.ipAddress().trim();
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

}
