package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipping_carriers")
public class ShippingCarrierEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 60, updatable = false)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(length = 500)
    private String description;

    @Column(name = "provider_code", nullable = false, length = 40, updatable = false)
    private String providerCode;

    @Column(name = "coverage_scope", nullable = false, length = 30, updatable = false)
    private String coverageScope;

    @Column(name = "pricing_mode", nullable = false, length = 24)
    private String pricingMode = "LEGACY";

    @Column(name = "customer_fee", nullable = false)
    private long customerFee;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "default_for_new_orders", nullable = false)
    private boolean defaultForNewOrders;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long version;

    @Column(name = "availability_version", nullable = false)
    private long availabilityVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getCoverageScope() { return coverageScope; }
    public void setCoverageScope(String coverageScope) { this.coverageScope = coverageScope; }
    public String getPricingMode() { return pricingMode; }
    public void setPricingMode(String pricingMode) { this.pricingMode = pricingMode; }
    public long getCustomerFee() { return customerFee; }
    public void setCustomerFee(long customerFee) { this.customerFee = customerFee; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isDefaultForNewOrders() { return defaultForNewOrders; }
    public void setDefaultForNewOrders(boolean defaultForNewOrders) { this.defaultForNewOrders = defaultForNewOrders; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public long getVersion() { return version; }
    public long getAvailabilityVersion() { return availabilityVersion; }
    public void incrementAvailabilityVersion() { availabilityVersion = Math.addExact(availabilityVersion, 1); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
