package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PaymentAttemptStatus;
import com.utewedding.app.domain.PaymentProviderEnvironment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "order_id") private UUID orderId;
    @Column(name = "checkout_group_id") private UUID checkoutGroupId;
    @Column(nullable = false, length = 60) private String provider;
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_environment", length = 16, updatable = false)
    private PaymentProviderEnvironment providerEnvironment;
    @Column(name = "merchant_reference", nullable = false, length = 80) private String merchantReference;
    @Column(name = "provider_request_id", length = 190) private String providerRequestId;
    @Column(name = "provider_transaction_id", length = 190) private String providerTransactionId;
    /** VNPay {@code vnp_CreateDate} (GMT+7 {@code yyyyMMddHHmmss}) used by query and refund. */
    @Column(name = "provider_create_date", length = 14) private String providerCreateDate;
    /** Unique VNPay payment-session reference; null for historical attempts. */
    @Column(name = "provider_txn_ref", length = 80) private String providerTxnRef;
    @Column(name = "provider_order_info", length = 255) private String providerOrderInfo;
    @Column(name = "provider_client_ip", length = 45) private String providerClientIp;
    @Column(nullable = false) private long amount;
    @Column(nullable = false, length = 3) private String currency = "VND";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private PaymentAttemptStatus status;
    @Column(name = "failure_code", length = 40) private String failureCode;
    @Column(name = "failure_reason", length = 500) private String failureReason;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public UUID getCheckoutGroupId() { return checkoutGroupId; }
    public void setCheckoutGroupId(UUID value) { checkoutGroupId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public PaymentProviderEnvironment getProviderEnvironment() { return providerEnvironment; }
    public void setProviderEnvironment(PaymentProviderEnvironment value) { providerEnvironment = value; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String value) { merchantReference = value; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String value) { providerRequestId = value; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String value) { providerTransactionId = value; }
    public String getProviderCreateDate() { return providerCreateDate; }
    public void setProviderCreateDate(String value) { providerCreateDate = value; }
    public String getProviderTxnRef() { return providerTxnRef; }
    public void setProviderTxnRef(String value) { providerTxnRef = value; }
    public String getProviderOrderInfo() { return providerOrderInfo; }
    public void setProviderOrderInfo(String value) { providerOrderInfo = value; }
    public String getProviderClientIp() { return providerClientIp; }
    public void setProviderClientIp(String value) { providerClientIp = value; }
    public long getAmount() { return amount; }
    public void setAmount(long value) { amount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public PaymentAttemptStatus getStatus() { return status; }
    public void setStatus(PaymentAttemptStatus value) { status = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { failureReason = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PaymentAttemptEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
