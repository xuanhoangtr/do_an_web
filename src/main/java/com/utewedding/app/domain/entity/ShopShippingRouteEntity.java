package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_shipping_routes")
public class ShopShippingRouteEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "binding_id", nullable = false)
    private UUID bindingId;
    @Column(name = "shop_id", nullable = false)
    private UUID shopId;
    @Column(name = "catalog_source", nullable = false, length = 32)
    private String catalogSource;
    @Column(name = "province_code", nullable = false, length = 20)
    private String provinceCode;
    @Column(name = "province_name", nullable = false, length = 255)
    private String provinceName;
    @Column(name = "ward_code", nullable = false, length = 20)
    private String wardCode;
    @Column(name = "ward_name", nullable = false, length = 255)
    private String wardName;
    @Column(nullable = false, length = 24)
    private String status = "PENDING_VERIFICATION";
    @Column(nullable = false)
    private boolean active;
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

    protected ShopShippingRouteEntity() { }
    public ShopShippingRouteEntity(UUID bindingId, UUID shopId, String catalogSource,
                                   String provinceCode, String provinceName, String wardCode, String wardName,
                                   UUID createdBy) {
        this.bindingId = bindingId; this.shopId = shopId; this.catalogSource = catalogSource;
        this.provinceCode = provinceCode; this.provinceName = provinceName; this.wardCode = wardCode;
        this.wardName = wardName; this.createdBy = createdBy; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getBindingId() { return bindingId; }
    public UUID getShopId() { return shopId; }
    public String getCatalogSource() { return catalogSource; }
    public String getProvinceCode() { return provinceCode; }
    public String getProvinceName() { return provinceName; }
    public String getWardCode() { return wardCode; }
    public String getWardName() { return wardName; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public String getEvidenceRedacted() { return evidenceRedacted; }
    public void setEvidenceRedacted(String value) { evidenceRedacted = value; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(UUID value) { verifiedBy = value; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant value) { verifiedAt = value; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ShopShippingRouteEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
