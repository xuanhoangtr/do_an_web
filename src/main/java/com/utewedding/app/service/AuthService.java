package com.utewedding.app.service;

import com.utewedding.app.dto.request.ChangePasswordRequest;
import com.utewedding.app.dto.request.LoginRequest;
import com.utewedding.app.dto.request.RegisterRequest;
import com.utewedding.app.dto.response.AuthResponse;
import com.utewedding.app.dto.response.MessageResponse;
import java.util.UUID;

/** Authentication operations backed by RS256 Bearer JWTs. */
public interface AuthService {
    String REGISTRATION_ACCEPTED_MESSAGE =
            "Nếu email có thể đăng ký, mã OTP xác thực sẽ được gửi sớm.";

    MessageResponse register(RegisterRequest request, RequestContext context);
    AuthResponse login(LoginRequest request, RequestContext context);
    AuthResponse refresh(String refreshToken, RequestContext context);
    MessageResponse logout(String refreshToken, UUID userId, RequestContext context);
    MessageResponse changePassword(UUID userId, ChangePasswordRequest request, RequestContext context,
                                   String currentWebSessionId);
}
