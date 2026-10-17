package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Canonical Vietnamese province/city master data imported from the pinned dataset. */
@Entity
@Table(name = "provinces")
public class ProvinceEntity {
    @Id
    @Column(length = 20)
    private String code;
    @Column(nullable = false, length = 255)
    private String name;
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;
    @Column(name = "code_name", length = 255)
    private String codeName;
    @Column(name = "administrative_unit_id")
    private Integer administrativeUnitId;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Column(name = "catalog_source", nullable = false, length = 32)
    private String catalogSource = "LOCAL";
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aliases", columnDefinition = "jsonb", nullable = false)
    private String aliases = "[]";
    @Column(name = "catalog_type", length = 24)
    private String catalogType;
    @Column(name = "provider_status")
    private Integer providerStatus;
    @Column(name = "catalog_synced_at")
    private Instant catalogSyncedAt;

    protected ProvinceEntity() {}

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getFullName() { return fullName; }
    public String getCodeName() { return codeName; }
    public Integer getAdministrativeUnitId() { return administrativeUnitId; }
    public boolean isActive() { return active; }
    public String getCatalogSource() { return catalogSource; }
    public String getAliases() { return aliases; }
    public String getCatalogType() { return catalogType; }
    public Integer getProviderStatus() { return providerStatus; }
    public Instant getCatalogSyncedAt() { return catalogSyncedAt; }

    public void setCode(String value) { code = value; }
    public void setName(String value) { name = value; }
    public void setFullName(String value) { fullName = value; }
    public void setCodeName(String value) { codeName = value; }
    public void setAdministrativeUnitId(Integer value) { administrativeUnitId = value; }
    public void setActive(boolean value) { active = value; }
    public void setCatalogSource(String value) { catalogSource = value; }
    public void setAliases(String value) { aliases = value; }
    public void setCatalogType(String value) { catalogType = value; }
    public void setProviderStatus(Integer value) { providerStatus = value; }
    public void setCatalogSyncedAt(Instant value) { catalogSyncedAt = value; }
}
