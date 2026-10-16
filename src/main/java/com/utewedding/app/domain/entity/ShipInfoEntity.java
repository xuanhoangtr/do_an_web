package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Convert;
import com.utewedding.app.security.EncryptedStringConverter;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Saved address aggregate. Orders copy its values as an immutable snapshot. */
@Entity
@Table(name = "ship_infos")
public class ShipInfoEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(length = 60) private String label;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipient_name", nullable = false, length = 4096) private String recipientName;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 4096) private String phone;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "address_line", nullable = false, length = 4096) private String addressLine;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String ward;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String district;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String province;
    @Column(name = "province_code", length = 20) private String provinceCode;
    @Column(name = "province_name", length = 255) private String provinceName;
    @Column(name = "ward_code", length = 20) private String wardCode;
    @Column(name = "ward_name", length = 255) private String wardName;
    @Column(name = "address_catalog_source", nullable = false, length = 32) private String addressCatalogSource = "LOCAL";
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "postal_code", length = 4096) private String postalCode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String note;
    @Column(name = "is_default", nullable = false) private boolean defaultAddress;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public ShipInfoEntity() {}
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public String getLabel() { return label; }
    public void setLabel(String value) { label = value; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String value) { recipientName = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String value) { addressLine = value; }
    public String getWard() { return ward; }
    public void setWard(String value) { ward = value; }
    public String getDistrict() { return district; }
    public void setDistrict(String value) { district = value; }
    public String getProvince() { return province; }
    public void setProvince(String value) { province = value; }
    public String getProvinceCode() { return provinceCode; }
    public void setProvinceCode(String value) { provinceCode = value; }
    public String getProvinceName() { return provinceName; }
    public void setProvinceName(String value) { provinceName = value; }
    public String getWardCode() { return wardCode; }
    public void setWardCode(String value) { wardCode = value; }
    public String getWardName() { return wardName; }
    public void setWardName(String value) { wardName = value; }
    public String getAddressCatalogSource() { return addressCatalogSource; }
    public void setAddressCatalogSource(String value) { addressCatalogSource = value; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String value) { postalCode = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public boolean isDefaultAddress() { return defaultAddress; }
    public void setDefaultAddress(boolean value) { defaultAddress = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ShipInfoEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
