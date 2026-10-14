package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
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
import jakarta.persistence.Convert;
import com.utewedding.app.security.EncryptedStringConverter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_user_status", columnList = "user_id, status"),
        @Index(name = "idx_orders_wedding_status", columnList = "wedding_id, status"),
        @Index(name = "idx_orders_status_created_at", columnList = "status, created_at"),
        @Index(name = "idx_orders_shipper_status", columnList = "shipper_id, status, created_at")
})
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "wedding_id") private UUID weddingId;
    @Column(name = "ship_info_id") private UUID shipInfoId;
    @Column(name = "order_number", nullable = false, unique = true, length = 40) private String orderNumber;
    @Column(name = "tracking_access_code", nullable = false, unique = true) private UUID trackingAccessCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private OrderStatus status = OrderStatus.PENDING;
    @Column(nullable = false) private long subtotal;
    @Column(name = "discount_amount", nullable = false) private long discountAmount;
    @Column(name = "total_amount", nullable = false) private long totalAmount;
    @Column(nullable = false, length = 3) private String currency = "VND";
    @Column(name = "discount_code", length = 80) private String discountCode;
    @Column(name = "idempotency_key", unique = true, length = 120) private String idempotencyKey;
    @Column(name = "payment_method", length = 40) private String paymentMethod;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 32)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_recipient_name", length = 4096) private String shippingRecipientName;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_phone", length = 4096) private String shippingPhone;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_address", length = 4096) private String shippingAddress;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_ward", length = 4096) private String shippingWard;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_district", length = 4096) private String shippingDistrict;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_province", length = 4096) private String shippingProvince;
    @Column(name = "shipping_province_code", length = 20) private String shippingProvinceCode;
    @Column(name = "shipping_province_name", length = 255) private String shippingProvinceName;
    @Column(name = "shipping_ward_code", length = 20) private String shippingWardCode;
    @Column(name = "shipping_ward_name", length = 255) private String shippingWardName;
    @Column(name = "shipping_address_catalog_source", nullable = false, length = 32)
    private String shippingAddressCatalogSource = "LOCAL";
    @Column(name = "shipping_fee", nullable = false) private long shippingFee;
    @Column(name = "shipping_fee_before_discount", nullable = false) private long shippingFeeBeforeDiscount;
    @Column(name = "shipping_discount_amount", nullable = false) private long shippingDiscountAmount;
    @Column(name = "voucher_discount_amount", nullable = false) private long voucherDiscountAmount;
    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", length = 20) private com.utewedding.app.domain.VoucherType voucherType;
    @Column(name = "shipper_id") private UUID shipperId;
    @Column(name = "carrier_id") private UUID carrierId;
    @Column(name = "tracking_number", length = 120) private String trackingNumber;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "confirmed_by") private UUID confirmedBy;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancel_reason", length = 500) private String cancelReason;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "returned_at") private Instant returnedAt;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public OrderEntity() {
    }

    public OrderEntity(UUID userId, Instant createdAt) {
        this.userId = userId;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (trackingAccessCode == null) trackingAccessCode = UUID.randomUUID();
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = createdAt;
    }
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public UUID getShipInfoId() { return shipInfoId; }
    public void setShipInfoId(UUID value) { shipInfoId = value; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String value) { orderNumber = value; }
    public UUID getTrackingAccessCode() { return trackingAccessCode; }
    public void setTrackingAccessCode(UUID value) { trackingAccessCode = value; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus value) { status = value; }
    /** Validated business transition for application writes. */
    public void transitionTo(OrderStatus target) {
        if (target == null || status == null || (status != target && !status.canTransitionTo(target))) {
            throw new IllegalStateException("Invalid order status transition: " + status + " -> " + target);
        }
        status = target;
    }
    public long getSubtotal() { return subtotal; }
    public void setSubtotal(long value) { subtotal = value; }
    public long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(long value) { discountAmount = value; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long value) { totalAmount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getDiscountCode() { return discountCode; }
    public void setDiscountCode(String value) { discountCode = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String value) { paymentMethod = value; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus value) { paymentStatus = value; }
    public String getShippingRecipientName() { return shippingRecipientName; }
    public void setShippingRecipientName(String value) { shippingRecipientName = value; }
    public String getShippingPhone() { return shippingPhone; }
    public void setShippingPhone(String value) { shippingPhone = value; }
    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String value) { shippingAddress = value; }
    public String getShippingWard() { return shippingWard; }
    public void setShippingWard(String value) { shippingWard = value; }
    public String getShippingDistrict() { return shippingDistrict; }
    public void setShippingDistrict(String value) { shippingDistrict = value; }
    public String getShippingProvince() { return shippingProvince; }
    public void setShippingProvince(String value) { shippingProvince = value; }
    public String getShippingProvinceCode() { return shippingProvinceCode; }
    public void setShippingProvinceCode(String value) { shippingProvinceCode = value; }
    public String getShippingProvinceName() { return shippingProvinceName; }
    public void setShippingProvinceName(String value) { shippingProvinceName = value; }
    public String getShippingWardCode() { return shippingWardCode; }
    public void setShippingWardCode(String value) { shippingWardCode = value; }
    public String getShippingWardName() { return shippingWardName; }
    public void setShippingWardName(String value) { shippingWardName = value; }
    public String getShippingAddressCatalogSource() { return shippingAddressCatalogSource; }
    public void setShippingAddressCatalogSource(String value) { shippingAddressCatalogSource = value; }
    public long getShippingFee() { return shippingFee; }
    public void setShippingFee(long value) { shippingFee = value; }
    public long getShippingFeeBeforeDiscount() { return shippingFeeBeforeDiscount; }
    public void setShippingFeeBeforeDiscount(long value) { shippingFeeBeforeDiscount = value; }
    public long getShippingDiscountAmount() { return shippingDiscountAmount; }
    public void setShippingDiscountAmount(long value) { shippingDiscountAmount = value; }
    public long getVoucherDiscountAmount() { return voucherDiscountAmount; }
    public void setVoucherDiscountAmount(long value) { voucherDiscountAmount = value; }
    public com.utewedding.app.domain.VoucherType getVoucherType() { return voucherType; }
    public void setVoucherType(com.utewedding.app.domain.VoucherType value) { voucherType = value; }
    public UUID getShipperId() { return shipperId; }
    public void setShipperId(UUID value) { shipperId = value; }
    public UUID getCarrierId() { return carrierId; }
    public void setCarrierId(UUID value) { carrierId = value; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String value) { trackingNumber = value; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant value) { paidAt = value; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant value) { confirmedAt = value; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(UUID value) { confirmedBy = value; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant value) { cancelledAt = value; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String value) { cancelReason = value; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant value) { deliveredAt = value; }
    public Instant getReturnedAt() { return returnedAt; }
    public void setReturnedAt(Instant value) { returnedAt = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OrderEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
