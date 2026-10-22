package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ReturnCaseStatus;
import com.utewedding.app.domain.ShopDefaults;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "return_cases", indexes = {
        @Index(name = "idx_return_cases_customer_status", columnList = "customer_id, status, created_at"),
        @Index(name = "idx_return_cases_fulfillment", columnList = "fulfillment_id")
})
public class ReturnCaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "shop_id", nullable = false, updatable = false) private UUID shopId = ShopDefaults.PLATFORM_SHOP_ID;
    @Column(name = "case_number", nullable = false, unique = true, length = 40) private String caseNumber;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "fulfillment_id", nullable = false) private UUID fulfillmentId;
    @Column(name = "customer_id", nullable = false) private UUID customerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private ReturnCaseStatus status = ReturnCaseStatus.REQUESTED;
    @Column(name = "return_required", nullable = false) private boolean returnRequired = true;
    @Column(nullable = false, length = 80) private String reason;
    @Column(name = "policy_snapshot", nullable = false, length = 2000) private String policySnapshot;
    @Column(name = "request_deadline", nullable = false) private Instant requestDeadline;
    @Column(name = "ship_deadline") private Instant shipDeadline;
    @Column(name = "dispute_deadline") private Instant disputeDeadline;
    @Column(name = "reviewed_by") private UUID reviewedBy;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "rejection_reason", length = 1000) private String rejectionReason;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected ReturnCaseEntity() {}

    public ReturnCaseEntity(String caseNumber, UUID orderId, UUID fulfillmentId, UUID customerId,
                            String reason, String policySnapshot, Instant requestDeadline) {
        this.caseNumber = caseNumber;
        this.orderId = orderId;
        this.fulfillmentId = fulfillmentId;
        this.customerId = customerId;
        this.reason = reason;
        this.policySnapshot = policySnapshot;
        this.requestDeadline = requestDeadline;
    }

    public void transitionTo(ReturnCaseStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid return case transition: " + status + " -> " + target);
        }
        status = target;
    }

    @PrePersist void onCreate() {
        if (shopId == null) shopId = ShopDefaults.PLATFORM_SHOP_ID;
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public String getCaseNumber() { return caseNumber; }
    public UUID getOrderId() { return orderId; }
    public UUID getFulfillmentId() { return fulfillmentId; }
    public UUID getCustomerId() { return customerId; }
    public ReturnCaseStatus getStatus() { return status; }
    public boolean isReturnRequired() { return returnRequired; }
    public void setReturnRequired(boolean value) { returnRequired = value; }
    public String getReason() { return reason; }
    public String getPolicySnapshot() { return policySnapshot; }
    public Instant getRequestDeadline() { return requestDeadline; }
    public Instant getShipDeadline() { return shipDeadline; }
    public void setShipDeadline(Instant value) { shipDeadline = value; }
    public Instant getDisputeDeadline() { return disputeDeadline; }
    public void setDisputeDeadline(Instant value) { disputeDeadline = value; }
    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID value) { reviewedBy = value; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant value) { reviewedAt = value; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String value) { rejectionReason = value; }
    public long getVersion() { return version; }
}
