/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  jakarta.persistence.Column
 *  jakarta.persistence.Entity
 *  jakarta.persistence.EnumType
 *  jakarta.persistence.Enumerated
 *  jakarta.persistence.GeneratedValue
 *  jakarta.persistence.GenerationType
 *  jakarta.persistence.Id
 *  jakarta.persistence.PrePersist
 *  jakarta.persistence.PreUpdate
 *  jakarta.persistence.Table
 *  jakarta.persistence.Version
 */
package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.FulfillmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name="fulfillment_shipments")
public class FulfillmentShipmentEntity {
    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    private UUID id;
    @Column(name="fulfillment_id", nullable=false)
    private UUID fulfillmentId;
    @Column(name="provider_code", nullable=false, length=40)
    private String providerCode;
    @Column(name="method_code", nullable=false, length=40)
    private String methodCode;
    @Column(name="service_code", nullable=false, length=80)
    private String serviceCode;
    @Column(name="provider_shipment_id", length=190)
    private String providerShipmentId;
    @Column(name="provider_reference", length=190)
    private String providerReference;
    @Column(name="tracking_number", length=120)
    private String trackingNumber;
    @Column(name="label_reference", length=512)
    private String labelReference;
    @Enumerated(value=EnumType.STRING)
    @Column(nullable=false, length=30)
    private FulfillmentStatus status = FulfillmentStatus.PICKUP_PENDING;
    @Column(name="last_provider_sequence")
    private Long lastProviderSequence;
    @Column(name="last_provider_event_at")
    private Instant lastProviderEventAt;
    @Column(name="cancelled_at")
    private Instant cancelledAt;
    @Version
    @Column(nullable=false)
    private long version;
    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt;
    @Column(name="updated_at", nullable=false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        Instant n = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = n;
        }
        this.updatedAt = n;
    }

    @PreUpdate
    void update() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return this.id;
    }

    public UUID getFulfillmentId() {
        return this.fulfillmentId;
    }

    public void setFulfillmentId(UUID v) {
        this.fulfillmentId = v;
    }

    public String getProviderCode() {
        return this.providerCode;
    }

    public void setProviderCode(String v) {
        this.providerCode = v;
    }

    public String getMethodCode() {
        return this.methodCode;
    }

    public void setMethodCode(String v) {
        this.methodCode = v;
    }

    public String getServiceCode() {
        return this.serviceCode;
    }

    public void setServiceCode(String v) {
        this.serviceCode = v;
    }

    public String getProviderShipmentId() {
        return this.providerShipmentId;
    }

    public void setProviderShipmentId(String v) {
        this.providerShipmentId = v;
    }

    public String getProviderReference() {
        return this.providerReference;
    }

    public void setProviderReference(String v) {
        this.providerReference = v;
    }

    public String getTrackingNumber() {
        return this.trackingNumber;
    }

    public void setTrackingNumber(String v) {
        this.trackingNumber = v;
    }

    public String getLabelReference() {
        return this.labelReference;
    }

    public void setLabelReference(String v) {
        this.labelReference = v;
    }

    public FulfillmentStatus getStatus() {
        return this.status;
    }

    public void setStatus(FulfillmentStatus v) {
        this.status = v;
    }

    public Long getLastProviderSequence() {
        return this.lastProviderSequence;
    }

    public void setLastProviderSequence(Long v) {
        this.lastProviderSequence = v;
    }

    public Instant getLastProviderEventAt() {
        return this.lastProviderEventAt;
    }

    public void setLastProviderEventAt(Instant v) {
        this.lastProviderEventAt = v;
    }

    public void setCancelledAt(Instant v) {
        this.cancelledAt = v;
    }
}
