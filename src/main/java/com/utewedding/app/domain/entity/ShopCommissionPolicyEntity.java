package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_commission_policies", indexes = {
        @Index(name = "idx_shop_commission_policy_effective", columnList = "effective_at,policy_version")
})
public class ShopCommissionPolicyEntity {
    @Id
    @Column(name = "policy_version", nullable = false)
    private Long policyVersion;

    @Column(name = "rate_bps", nullable = false)
    private int rateBps;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopCommissionPolicyEntity() { }

    public ShopCommissionPolicyEntity(Long policyVersion, int rateBps, Instant effectiveAt, UUID createdBy) {
        this.policyVersion = policyVersion;
        this.rateBps = rateBps;
        this.effectiveAt = effectiveAt;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getPolicyVersion() { return policyVersion; }
    public int getRateBps() { return rateBps; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
