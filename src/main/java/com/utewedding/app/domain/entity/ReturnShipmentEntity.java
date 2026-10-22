package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ReturnShipmentDirection;
import com.utewedding.app.domain.ReturnShipmentStatus;
import com.utewedding.app.domain.ShopDefaults;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "return_shipments", indexes = {
        @Index(name = "idx_return_shipments_case_status", columnList = "return_case_id, status")
})
public class ReturnShipmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "shop_id", nullable = false, updatable = false) private UUID shopId = ShopDefaults.PLATFORM_SHOP_ID;
    @Column(name = "provider_account_id") private UUID providerAccountId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "provider_context_snapshot", columnDefinition = "jsonb")
    private String providerContextSnapshot;
    @Column(name = "return_case_id", nullable = false) private UUID returnCaseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReturnShipmentDirection direction;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReturnShipmentStatus status = ReturnShipmentStatus.CREATED;
    @Column(length = 120) private String carrier;
    @Column(name = "tracking_number", length = 120) private String trackingNumber;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ReturnShipmentEntity() {}
    public ReturnShipmentEntity(UUID caseId, ReturnShipmentDirection direction) { this.returnCaseId = caseId; this.direction = direction; this.createdAt = Instant.now(); this.updatedAt = createdAt; }
    public UUID getId() { return id; }
    @PrePersist void onCreate() { if (shopId == null) shopId = ShopDefaults.PLATFORM_SHOP_ID; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(UUID value) { providerAccountId = value; }
    public String getProviderContextSnapshot() { return providerContextSnapshot; }
    public void setProviderContextSnapshot(String value) { providerContextSnapshot = value; }
    public UUID getReturnCaseId() { return returnCaseId; }
    public ReturnShipmentDirection getDirection() { return direction; }
    public ReturnShipmentStatus getStatus() { return status; }
    public void setStatus(ReturnShipmentStatus value) { status = value; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String value) { carrier = value; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String value) { trackingNumber = value; }
}
