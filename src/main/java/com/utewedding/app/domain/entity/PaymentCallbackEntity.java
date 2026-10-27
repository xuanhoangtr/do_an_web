package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PaymentCallbackOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_callbacks")
public class PaymentCallbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, length = 60) private String provider;
    @Column(name = "order_id") private UUID orderId;
    @Column(name = "checkout_group_id") private UUID checkoutGroupId;
    @Column(name = "attempt_id") private UUID attemptId;
    @Column(name = "provider_transaction_id", length = 190) private String providerTransactionId;
    @Column(name = "signature_valid", nullable = false) private boolean signatureValid;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30) private PaymentCallbackOutcome outcome;
    @Column(name = "response_code", length = 40) private String responseCode;
    @Column(name = "transaction_status", length = 40) private String transactionStatus;
    private Long amount;
    @Column(name = "payload_sha256", nullable = false, length = 64) private String payloadSha256;
    @Column(name = "raw_payload", nullable = false, length = 4000) private String rawPayload;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public UUID getCheckoutGroupId() { return checkoutGroupId; }
    public void setCheckoutGroupId(UUID value) { checkoutGroupId = value; }
    public UUID getAttemptId() { return attemptId; }
    public void setAttemptId(UUID value) { attemptId = value; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String value) { providerTransactionId = value; }
    public boolean isSignatureValid() { return signatureValid; }
    public void setSignatureValid(boolean value) { signatureValid = value; }
    public PaymentCallbackOutcome getOutcome() { return outcome; }
    public void setOutcome(PaymentCallbackOutcome value) { outcome = value; }
    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String value) { responseCode = value; }
    public String getTransactionStatus() { return transactionStatus; }
    public void setTransactionStatus(String value) { transactionStatus = value; }
    public Long getAmount() { return amount; }
    public void setAmount(Long value) { amount = value; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String value) { payloadSha256 = value; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String value) { rawPayload = value; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant value) { receivedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PaymentCallbackEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
