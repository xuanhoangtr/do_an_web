package com.utewedding.app.service.impl;

import com.utewedding.app.observability.CommerceMetrics;
import com.utewedding.app.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** SMTP implementation with non-fatal delivery failures. */
@Service
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String driver;
    private CommerceMetrics metrics = CommerceMetrics.noop();

    public SmtpEmailService(JavaMailSender mailSender,
                            @Value("${mail.from:no-reply@utewedding.local}") String from,
                            @Value("${mail.driver:smtp}") String driver) {
        this.mailSender = mailSender;
        this.from = from;
        this.driver = driver;
    }

    @Autowired(required = false)
    void attachMetrics(CommerceMetrics metrics) {
        this.metrics = metrics == null ? CommerceMetrics.noop() : metrics;
    }

    @Override
    public void sendVerificationEmail(String recipient, String verificationLink) {
        send("verification", recipient, "Xác thực email UTEWedding",
                "Vui lòng mở liên kết sau để xác thực email của bạn:\n" + verificationLink);
    }

    @Override
    public void sendVerificationEmail(String recipient, String otp, String verificationLink) {
        send("verification", recipient, "Xác thực email UTEWedding",
                "Mã xác thực email của bạn là: " + otp + "\n\n"
                        + "Mã có hiệu lực trong 10 phút. Bạn cũng có thể mở liên kết sau:\n"
                        + verificationLink);
    }

    @Override
    public void sendPasswordResetEmail(String recipient, String resetLink) {
        send("password_reset", recipient, "Khôi phục mật khẩu UTEWedding",
                "Liên kết khôi phục mật khẩu của bạn (có thời hạn):\n" + resetLink);
    }

    @Override
    public void sendPasswordResetEmail(String recipient, String otp, String resetLink) {
        send("password_reset", recipient, "Khôi phục mật khẩu UTEWedding",
                "Mã OTP khôi phục mật khẩu của bạn là: " + otp + "\n\n"
                        + "Mã có hiệu lực trong 10 phút. Bạn cũng có thể mở liên kết sau:\n" + resetLink);
    }

    @Override
    public void sendOrderNotification(String recipient, String orderNumber, long amount, String currency) {
        send("order", recipient, "Cập nhật đơn hàng " + orderNumber,
                "Đơn hàng " + orderNumber + " đã được ghi nhận với giá trị " + amount + " " + currency + ".");
    }

    private void send(String kind, String recipient, String subject, String body) {
        if (recipient == null || recipient.isBlank() || "noop".equalsIgnoreCase(driver)) {
            metrics.email(kind, "skipped");
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(recipient.trim());
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            metrics.email(kind, "sent");
        } catch (MailException | IllegalArgumentException exception) {
            // Mail delivery must not roll back registration or checkout.
            metrics.email(kind, "failed");
            log.warn("Email delivery failed for recipient {}", redact(recipient), exception);
        }
    }

    private String redact(String recipient) {
        int at = recipient.indexOf('@');
        if (at <= 1) return "***";
        return recipient.substring(0, 1) + "***" + recipient.substring(at);
    }
}
