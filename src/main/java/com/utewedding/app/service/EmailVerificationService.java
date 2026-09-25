package com.utewedding.app.service;

import java.util.UUID;
public interface EmailVerificationService {
    void issue(UUID userId, String email, RequestContext context);
    com.utewedding.app.dto.response.MessageResponse verifyOtp(
            com.utewedding.app.dto.request.VerifyEmailRequest request, RequestContext context);
    com.utewedding.app.dto.response.MessageResponse verifyLink(String token, RequestContext context);
    com.utewedding.app.dto.response.MessageResponse resend(
            com.utewedding.app.dto.request.ResendVerificationRequest request, RequestContext context);
}
