package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.InspectionResult;
import com.utewedding.app.domain.ReturnItemResolution;
import com.utewedding.app.domain.StockDisposition;
import com.utewedding.app.domain.ShopDefaults;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import java.util.UUID;

@Entity
@Table(name = "return_case_items")
public class ReturnCaseItemEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "shop_id", nullable = false, updatable = false) private UUID shopId = ShopDefaults.PLATFORM_SHOP_ID;
    @Column(name = "return_case_id", nullable = false) private UUID returnCaseId;
    @Column(name = "order_item_id", nullable = false) private UUID orderItemId;
    @Column(name = "requested_quantity", nullable = false) private int requestedQuantity;
    @Column(name = "approved_quantity", nullable = false) private int approvedQuantity;
    @Column(name = "received_quantity", nullable = false) private int receivedQuantity;
    @Column(name = "resolved_quantity", nullable = false) private int resolvedQuantity;
    @Column(name = "refund_amount", nullable = false) private long refundAmount;
    @Enumerated(EnumType.STRING) @Column(name = "resolution_type", length = 20) private ReturnItemResolution resolutionType;
    @Enumerated(EnumType.STRING) @Column(name = "inspection_result", length = 20) private InspectionResult inspectionResult;
    @Enumerated(EnumType.STRING) @Column(name = "stock_disposition", length = 20) private StockDisposition stockDisposition;

    protected ReturnCaseItemEntity() {}
    public ReturnCaseItemEntity(UUID returnCaseId, UUID orderItemId, int requestedQuantity) {
        if (requestedQuantity < 1) throw new IllegalArgumentException("requestedQuantity must be positive");
        this.returnCaseId = returnCaseId; this.orderItemId = orderItemId; this.requestedQuantity = requestedQuantity;
    }
    public UUID getId() { return id; }
    @PrePersist void onCreate() { if (shopId == null) shopId = ShopDefaults.PLATFORM_SHOP_ID; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getReturnCaseId() { return returnCaseId; }
    public UUID getOrderItemId() { return orderItemId; }
    public int getRequestedQuantity() { return requestedQuantity; }
    public int getApprovedQuantity() { return approvedQuantity; }
    public void setApprovedQuantity(int value) { approvedQuantity = value; }
    public int getReceivedQuantity() { return receivedQuantity; }
    public void setReceivedQuantity(int value) { receivedQuantity = value; }
    public int getResolvedQuantity() { return resolvedQuantity; }
    public void setResolvedQuantity(int value) { resolvedQuantity = value; }
    public long getRefundAmount() { return refundAmount; }
    public void setRefundAmount(long value) { refundAmount = value; }
    public ReturnItemResolution getResolutionType() { return resolutionType; }
    public void setResolutionType(ReturnItemResolution value) { resolutionType = value; }
    public InspectionResult getInspectionResult() { return inspectionResult; }
    public void setInspectionResult(InspectionResult value) { inspectionResult = value; }
    public StockDisposition getStockDisposition() { return stockDisposition; }
    public void setStockDisposition(StockDisposition value) { stockDisposition = value; }
}
