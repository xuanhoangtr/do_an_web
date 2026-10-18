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
@Table(name = "shop_shipping_bindings")
public class ShopShippingBindingEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "shop_id", nullable = false)
    private UUID shopId;
    @Column(nullable = false)
    private int revision;
    @Column(name = "provider_account_id", nullable = false)
    private UUID providerAccountId;
    @Column(name = "route_mode", nullable = false, length = 24)
    private String routeMode = "LEGACY_ROUTES";
    @Column(name = "external_shop_id", nullable = false, length = 24)
    private String externalShopId;
    @Column(name = "pickup_address_revision_id", nullable = false)
    private UUID pickupAddressRevisionId;
    @Column(name = "pickup_address_kind", nullable = false, length = 12)
    private String pickupAddressKind = "PICKUP";
    @Column(name = "return_address_revision_id", nullable = false)
    private UUID returnAddressRevisionId;
    @Column(name = "return_address_kind", nullable = false, length = 12)
    private String returnAddressKind = "RETURN";
    @Column(nullable = false, length = 24)
    private String status = "PENDING_VERIFICATION";
    @Column(nullable = false)
    private boolean active;
    @Column(name = "route_coverage_verified", nullable = false)
    private boolean routeCoverageVerified;
    @Column(name = "cod_beneficiary_verified", nullable = false)
    private boolean codBeneficiaryVerified;
    @Column(name = "cod_beneficiary_evidence_redacted", length = 500)
    private String codBeneficiaryEvidenceRedacted;
    @Column(name = "cod_beneficiary_verified_by")
    private UUID codBeneficiaryVerifiedBy;
    @Column(name = "cod_beneficiary_verified_at")
    private Instant codBeneficiaryVerifiedAt;
    @Column(name = "evidence_redacted", length = 500)
    private String evidenceRedacted;
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

    protected ShopShippingBindingEntity() { }
    public ShopShippingBindingEntity(UUID shopId, int revision, UUID accountId, String externalShopId,
                                     UUID pickupAddressId, UUID returnAddressId, UUID createdBy) {
        this.shopId = shopId; this.revision = revision; this.providerAccountId = accountId;
        this.externalShopId = externalShopId; this.pickupAddressRevisionId = pickupAddressId;
        this.returnAddressRevisionId = returnAddressId; this.createdBy = createdBy;
        this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public int getRevision() { return revision; }
    public UUID getProviderAccountId() { return providerAccountId; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String value) { routeMode = value; }
    public String getExternalShopId() { return externalShopId; }
    public UUID getPickupAddressRevisionId() { return pickupAddressRevisionId; }
    public UUID getReturnAddressRevisionId() { return returnAddressRevisionId; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public boolean isRouteCoverageVerified() { return routeCoverageVerified; }
    public void setRouteCoverageVerified(boolean value) { routeCoverageVerified = value; }
    public boolean isCodBeneficiaryVerified() { return codBeneficiaryVerified; }
    public void setCodBeneficiaryVerified(boolean value) { codBeneficiaryVerified = value; }
    public String getCodBeneficiaryEvidenceRedacted() { return codBeneficiaryEvidenceRedacted; }
    public void setCodBeneficiaryEvidenceRedacted(String value) { codBeneficiaryEvidenceRedacted = value; }
    public UUID getCodBeneficiaryVerifiedBy() { return codBeneficiaryVerifiedBy; }
    public void setCodBeneficiaryVerifiedBy(UUID value) { codBeneficiaryVerifiedBy = value; }
    public Instant getCodBeneficiaryVerifiedAt() { return codBeneficiaryVerifiedAt; }
    public void setCodBeneficiaryVerifiedAt(Instant value) { codBeneficiaryVerifiedAt = value; }
    public String getEvidenceRedacted() { return evidenceRedacted; }
    public void setEvidenceRedacted(String value) { evidenceRedacted = value; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(UUID value) { verifiedBy = value; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant value) { verifiedAt = value; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ShopShippingBindingEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
