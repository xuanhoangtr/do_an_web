package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_correlations")
public class RiskCorrelationEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "order_id", nullable = false, unique = true) private UUID orderId;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(name = "voucher_hash", length = 64) private String voucherHash;
    @Column(name = "phone_hash", length = 64) private String phoneHash;
    @Column(name = "address_hash", length = 64) private String addressHash;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;

    @PrePersist void create() { if (occurredAt == null) occurredAt = Instant.now(); }
    public void setOrderId(UUID value) { orderId = value; }
    public void setAccountId(UUID value) { accountId = value; }
    public void setVoucherHash(String value) { voucherHash = value; }
    public void setPhoneHash(String value) { phoneHash = value; }
    public void setAddressHash(String value) { addressHash = value; }
}
