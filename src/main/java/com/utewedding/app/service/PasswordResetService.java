package com.utewedding.app.service;

import com.utewedding.app.dto.request.ResetPasswordRequest;
import com.utewedding.app.dto.response.MessageResponse;

public interface PasswordResetService {
    /**
     * Creates a reset challenge only for a registered account.
     * An unknown email is a no-op so callers can return one generic result.
     */
    void request(String email, String requestIp);
    MessageResponse reset(ResetPasswordRequest request, RequestContext context);
}
