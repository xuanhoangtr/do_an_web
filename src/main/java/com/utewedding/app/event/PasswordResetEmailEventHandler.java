package com.utewedding.app.event;

import com.utewedding.app.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** SMTP is external I/O and must not hold open the password-reset transaction. */
@Component
public class PasswordResetEmailEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailEventHandler.class);

    private final EmailService emailService;

    public PasswordResetEmailEventHandler(EmailService emailService) {
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        try {
            emailService.sendPasswordResetEmail(event.recipient(), event.otp(), event.resetLink());
        } catch (RuntimeException exception) {
            // The token has already committed. Delivery failure is operational
            // telemetry, never a reason to undo the reset request.
            log.warn("Password reset email delivery failed for recipient {}", redact(event.recipient()), exception);
        }
    }

    private static String redact(String recipient) {
        if (recipient == null || recipient.isBlank()) return "***";
        int at = recipient.indexOf('@');
        if (at <= 1) return "***";
        return recipient.substring(0, 1) + "***" + recipient.substring(at);
    }
}
