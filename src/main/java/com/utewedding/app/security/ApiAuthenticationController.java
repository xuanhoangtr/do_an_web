package com.utewedding.app.security;

import com.utewedding.app.dto.request.ForgotPasswordRequest;
import com.utewedding.app.dto.request.LoginRequest;
import com.utewedding.app.dto.request.RegisterRequest;
import com.utewedding.app.dto.request.ResendVerificationRequest;
import com.utewedding.app.dto.request.ResetPasswordRequest;
import com.utewedding.app.dto.request.VerifyEmailRequest;
import com.utewedding.app.dto.response.AuthResponse;
import com.utewedding.app.dto.response.MessageResponse;
import com.utewedding.app.exception.DuplicateRegistrationException;
import com.utewedding.app.service.AuthService;
import com.utewedding.app.service.EmailVerificationService;
import com.utewedding.app.service.PasswordResetService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST authentication adapter using RS256 Bearer access tokens. */
@RestController
public class ApiAuthenticationController {
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenCookieService refreshTokenCookieService;
    @Autowired
    public ApiAuthenticationController(AuthService authService, PasswordResetService passwordResetService,
                                       EmailVerificationService emailVerificationService,
                                       RefreshTokenCookieService refreshTokenCookieService) {
        this.authService = Objects.requireNonNull(authService, "authService");
        this.passwordResetService = Objects.requireNonNull(passwordResetService, "passwordResetService");
        this.emailVerificationService = Objects.requireNonNull(emailVerificationService, "emailVerificationService");
        this.refreshTokenCookieService = Objects.requireNonNull(refreshTokenCookieService, "refreshTokenCookieService");
    }

    @PostMapping({"/api/v1/auth/login", "/api/auth/login"})
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse) {
        AuthResponse response = authService.login(request, context(httpRequest));
        writeRefreshCookie(httpResponse, response);
        return response;
    }

    public AuthResponse login(@Valid LoginRequest request, HttpServletRequest httpRequest) {
        return login(request, httpRequest, null);
    }

    @PostMapping({"/api/v1/auth/register", "/api/auth/register"})
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public MessageResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        try {
            return authService.register(request, context(httpRequest));
        } catch (DuplicateRegistrationException duplicate) {
            return new MessageResponse(AuthService.REGISTRATION_ACCEPTED_MESSAGE);
        }
    }

    @PostMapping({"/api/v1/auth/verify-email", "/api/auth/verify-email"})
    public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request, HttpServletRequest httpRequest) {
        return emailVerificationService.verifyOtp(request, context(httpRequest));
    }

    @PostMapping({"/api/v1/auth/resend-verification", "/api/auth/resend-verification"})
    public MessageResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request,
                                               HttpServletRequest httpRequest) {
        return emailVerificationService.resend(request, context(httpRequest));
    }

    @GetMapping({"/api/v1/auth/verify-email", "/api/auth/verify-email"})
    public MessageResponse verifyEmailLink(@RequestParam String token, HttpServletRequest httpRequest) {
        return emailVerificationService.verifyLink(token, context(httpRequest));
    }

    @PostMapping({"/api/v1/auth/refresh", "/api/auth/refresh"})
    public AuthResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String token = cookieValue(httpRequest, refreshTokenCookieService.name());
        AuthResponse response = authService.refresh(token, context(httpRequest));
        writeRefreshCookie(httpResponse, response);
        return response;
    }

    @PostMapping({"/api/v1/auth/logout", "/api/auth/logout"})
    public MessageResponse logout(Authentication authentication,
                                  HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String token = cookieValue(httpRequest, refreshTokenCookieService.name());
        MessageResponse response = authService.logout(token, authenticatedUserId(authentication), context(httpRequest));
        if (httpResponse != null) refreshTokenCookieService.clear(httpResponse);
        return response;
    }

    @PostMapping({"/api/v1/auth/forgot-password", "/api/auth/forgot-password"})
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        try {
            passwordResetService.request(request.email(), httpRequest.getRemoteAddr());
        } catch (RuntimeException exception) {
            // Do not let account existence or reset-service state change the public response.
        }
        return new MessageResponse("Nếu email tồn tại, hướng dẫn sẽ được gửi sớm.");
    }

    @PostMapping({"/api/v1/auth/reset-password", "/api/auth/reset-password"})
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        return passwordResetService.reset(request, context(httpRequest));
    }

    @GetMapping({"/api/v1/auth/csrf", "/api/auth/csrf"})
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken()); }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"), request.getHeader("X-Request-ID"));
    }

    private UUID authenticatedUserId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal ? principal.getId() : null;
    }

    private void writeRefreshCookie(HttpServletResponse response, AuthResponse authResponse) {
        if (response == null || authResponse == null) return;
        Duration maxAge = authResponse.refreshTokenExpiresAt() == null ? null : Duration.between(Instant.now(), authResponse.refreshTokenExpiresAt());
        refreshTokenCookieService.write(response, authResponse.refreshToken(), maxAge);
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) return cookie.getValue().trim();
        }
        return null;
    }

    public record CsrfResponse(String token) { }
}
