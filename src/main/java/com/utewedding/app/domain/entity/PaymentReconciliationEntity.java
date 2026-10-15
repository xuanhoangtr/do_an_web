package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PaymentReconciliationReason;
import com.utewedding.app.domain.PaymentReconciliationResolution;
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
@Table(name = "payment_reconciliations")
public class PaymentReconciliationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "attempt_id") private UUID attemptId;
    @Column(nullable = false, length = 60) private String provider;
    @Column(name = "merchant_reference", nullable = false, length = 80) private String merchantReference;
    @Column(name = "local_status", nullable = false, length = 30) private String localStatus;
    @Column(name = "provider_status", length = 30) private String providerStatus;
    @Column(name = "provider_transaction_id", length = 190) private String providerTransactionId;
    @Column(name = "provider_amount") private Long providerAmount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40) private PaymentReconciliationReason reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30) private PaymentReconciliationResolution resolution;
    @Column(length = 500) private String detail;
    @Column(name = "checked_at", nullable = false) private Instant checkedAt;

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public UUID getAttemptId() { return attemptId; }
    public void setAttemptId(UUID value) { attemptId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String value) { merchantReference = value; }
    public String getLocalStatus() { return localStatus; }
    public void setLocalStatus(String value) { localStatus = value; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String value) { providerStatus = value; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String value) { providerTransactionId = value; }
    public Long getProviderAmount() { return providerAmount; }
    public void setProviderAmount(Long value) { providerAmount = value; }
    public PaymentReconciliationReason getReason() { return reason; }
    public void setReason(PaymentReconciliationReason value) { reason = value; }
    public PaymentReconciliationResolution getResolution() { return resolution; }
    public void setResolution(PaymentReconciliationResolution value) { resolution = value; }
    public String getDetail() { return detail; }
    public void setDetail(String value) { detail = value; }
    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant value) { checkedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PaymentReconciliationEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
