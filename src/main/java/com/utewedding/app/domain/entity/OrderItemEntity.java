package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(nullable = false, length = 120) private String productName;
    @Column(name = "unit_price", nullable = false) private long unitPrice;
    @Column(nullable = false) private int quantity;
    @Column(name = "discount_amount", nullable = false) private long discountAmount;
    @Column(name = "line_total", nullable = false) private long lineTotal;
    @Column(name = "voucher_discount_allocated", nullable = false) private long voucherDiscountAllocated;
    @Column(name = "net_paid_amount", nullable = false) private long netPaidAmount;
    @Column(name = "return_reserved_quantity", nullable = false) private int returnReservedQuantity;
    @Column(name = "return_resolved_quantity", nullable = false) private int returnResolvedQuantity;

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID value) { productId = value; }
    public String getProductName() { return productName; }
    public void setProductName(String value) { productName = value; }
    public long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(long value) { unitPrice = value; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int value) { quantity = value; }
    public long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(long value) { discountAmount = value; }
    public long getLineTotal() { return lineTotal; }
    public void setLineTotal(long value) { lineTotal = value; }
    public long getVoucherDiscountAllocated() { return voucherDiscountAllocated; }
    public void setVoucherDiscountAllocated(long value) { voucherDiscountAllocated = value; }
    public long getNetPaidAmount() { return netPaidAmount; }
    public void setNetPaidAmount(long value) { netPaidAmount = value; }
    public int getReturnReservedQuantity() { return returnReservedQuantity; }
    public void setReturnReservedQuantity(int value) { returnReservedQuantity = value; }
    public int getReturnResolvedQuantity() { return returnResolvedQuantity; }
    public void setReturnResolvedQuantity(int value) { returnResolvedQuantity = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OrderItemEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
