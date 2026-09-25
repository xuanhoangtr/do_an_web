package com.utewedding.app.service;

/** Application mail boundary; callers do not depend on JavaMail types. */
public interface EmailService {
    void sendVerificationEmail(String recipient, String verificationLink);

    default void sendVerificationEmail(String recipient, String otp, String verificationLink) {
        sendVerificationEmail(recipient, verificationLink);
    }
    void sendPasswordResetEmail(String recipient, String resetLink);

    default void sendPasswordResetEmail(String recipient, String otp, String resetLink) {
        sendPasswordResetEmail(recipient, resetLink);
    }
    void sendOrderNotification(String recipient, String orderNumber, long amount, String currency);
}
