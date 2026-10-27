package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ProviderTransactionStatus;
import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.PaymentProviderEnvironment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_provider_transactions")
public class PaymentProviderTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "order_id") private UUID orderId;
    @Column(name = "checkout_group_id") private UUID checkoutGroupId;
    @Column(name = "attempt_id") private UUID attemptId;
    @Column(name = "payment_id") private UUID paymentId;
    @Column(nullable = false, length = 60) private String provider;
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_environment", length = 16, updatable = false)
    private PaymentProviderEnvironment providerEnvironment;
    @Column(name = "provider_transaction_id", length = 190) private String providerTransactionId;
    @Column(name = "provider_request_id", length = 190) private String providerRequestId;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20) private ProviderTransactionType transactionType;
    @Column(nullable = false) private long amount;
    @Column(nullable = false, length = 3) private String currency = "VND";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private ProviderTransactionStatus status;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 190) private String idempotencyKey;
    @Column(name = "response_code", length = 40) private String responseCode;
    @Column(name = "response_message", length = 500) private String responseMessage;
    @Column(name = "raw_response", length = 4000) private String rawResponse;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "processed_at") private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @jakarta.persistence.Column(name="return_resolution_id") private UUID returnResolutionId;
    public void setReturnResolutionId(UUID value){returnResolutionId=value;}
    public UUID getReturnResolutionId(){return returnResolutionId;}
    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public UUID getCheckoutGroupId() { return checkoutGroupId; }
    public void setCheckoutGroupId(UUID value) { checkoutGroupId = value; }
    public UUID getAttemptId() { return attemptId; }
    public void setAttemptId(UUID value) { attemptId = value; }
    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID value) { paymentId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public PaymentProviderEnvironment getProviderEnvironment() { return providerEnvironment; }
    public void setProviderEnvironment(PaymentProviderEnvironment value) { providerEnvironment = value; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String value) { providerTransactionId = value; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String value) { providerRequestId = value; }
    public ProviderTransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(ProviderTransactionType value) { transactionType = value; }
    public long getAmount() { return amount; }
    public void setAmount(long value) { amount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public ProviderTransactionStatus getStatus() { return status; }
    public void setStatus(ProviderTransactionStatus value) { status = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String value) { responseCode = value; }
    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String value) { responseMessage = value; }
    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String value) { rawResponse = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { processedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PaymentProviderTransactionEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
