package com.utewedding.app.service;

/** Durable, database-backed quota for email verification sends. */
public interface EmailVerificationRateLimitService {
    void consume(String email, String ipAddress);
}
