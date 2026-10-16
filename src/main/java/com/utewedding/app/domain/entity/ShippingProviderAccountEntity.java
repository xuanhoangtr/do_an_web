package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipping_provider_accounts")
public class ShippingProviderAccountEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "provider_code", nullable = false, length = 24)
    private String providerCode;
    @Column(nullable = false, length = 16)
    private String environment;
    @Column(name = "account_name", nullable = false, length = 120)
    private String accountName;
    @Column(name = "credential_reference", nullable = false, length = 200)
    private String credentialReference;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "verification_evidence_redacted", length = 500)
    private String verificationEvidenceRedacted;
    @Column(name = "verified_by")
    private UUID verifiedBy;
    @Column(name = "verified_at")
    private Instant verifiedAt;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShippingProviderAccountEntity() { }
    public ShippingProviderAccountEntity(String providerCode, String environment, String accountName,
                                         String credentialReference, UUID createdBy) {
        this.providerCode = providerCode; this.environment = environment; this.accountName = accountName;
        this.credentialReference = credentialReference; this.createdBy = createdBy;
        this.status = "PENDING"; this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getProviderCode() { return providerCode; }
    public String getEnvironment() { return environment; }
    public String getAccountName() { return accountName; }
    public String getCredentialReference() { return credentialReference; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getVerificationEvidenceRedacted() { return verificationEvidenceRedacted; }
    public void setVerificationEvidenceRedacted(String value) { verificationEvidenceRedacted = value; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(UUID value) { verifiedBy = value; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant value) { verifiedAt = value; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ShippingProviderAccountEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
