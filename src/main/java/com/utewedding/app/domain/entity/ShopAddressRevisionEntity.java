package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ShopAddressKind;
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
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "shop_address_revisions")
public class ShopAddressRevisionEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;
    @Enumerated(EnumType.STRING)
    @Column(name = "address_kind", nullable = false, updatable = false, length = 12)
    private ShopAddressKind addressKind;
    @Column(nullable = false, updatable = false)
    private int revision;
    @Column(name = "catalog_source", nullable = false, updatable = false, length = 32)
    private String catalogSource;
    @Column(name = "province_code", nullable = false, updatable = false, length = 20)
    private String provinceCode;
    @Column(name = "province_name", nullable = false, updatable = false, length = 255)
    private String provinceName;
    @Column(name = "ward_code", nullable = false, updatable = false, length = 20)
    private String wardCode;
    @Column(name = "ward_name", nullable = false, updatable = false, length = 255)
    private String wardName;
    @Column(name = "address_line", nullable = false, updatable = false, length = 500)
    private String addressLine;
    @Column(name = "contact_name", nullable = false, updatable = false, length = 160)
    private String contactName;
    @Column(name = "contact_phone", nullable = false, updatable = false, length = 30)
    private String contactPhone;
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopAddressRevisionEntity() { }

    public ShopAddressRevisionEntity(UUID shopId, ShopAddressKind addressKind, int revision, String catalogSource,
                                     String provinceCode, String provinceName, String wardCode, String wardName,
                                     String addressLine, String contactName, String contactPhone, UUID createdBy) {
        this.shopId = shopId;
        this.addressKind = addressKind;
        this.revision = revision;
        this.catalogSource = catalogSource;
        this.provinceCode = provinceCode;
        this.provinceName = provinceName;
        this.wardCode = wardCode;
        this.wardName = wardName;
        this.addressLine = addressLine;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public ShopAddressKind getAddressKind() { return addressKind; }
    public int getRevision() { return revision; }
    public String getCatalogSource() { return catalogSource; }
    public String getProvinceCode() { return provinceCode; }
    public String getProvinceName() { return provinceName; }
    public String getWardCode() { return wardCode; }
    public String getWardName() { return wardName; }
    public String getAddressLine() { return addressLine; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ShopAddressRevisionEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
