package com.utewedding.app.event;

/**
 * Contains the one-time link needed by the mail adapter. The raw token is
 * never persisted; this event is delivered only after its token row commits.
 */
public record PasswordResetRequestedEvent(String recipient, String otp, String resetLink) {
    public PasswordResetRequestedEvent(String recipient, String resetLink) {
        this(recipient, null, resetLink);
    }
}
