package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.CheckoutGroupFulfillmentStatus;
import com.utewedding.app.domain.CheckoutGroupLifecycleStatus;
import com.utewedding.app.domain.CheckoutGroupPaymentStatus;
import com.utewedding.app.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
@Table(name = "checkout_groups", indexes = {
        @Index(name = "idx_checkout_groups_customer_created", columnList = "customer_id, created_at"),
        @Index(name = "idx_checkout_groups_payment_expiry", columnList = "payment_status, expires_at")
})
public class CheckoutGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "group_number", nullable = false, unique = true, length = 40)
    private String groupNumber;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;
    @Column(name = "ship_info_id")
    private UUID shipInfoId;
    @Column(name = "payment_method", nullable = false, length = 40)
    private String paymentMethod;
    @Column(nullable = false, length = 3)
    private String currency = "VND";

    @Column(nullable = false)
    private long subtotal;
    @Column(name = "product_discount_amount", nullable = false)
    private long productDiscountAmount;
    @Column(name = "voucher_discount_amount", nullable = false)
    private long voucherDiscountAmount;
    @Column(name = "shipping_fee_before_discount", nullable = false)
    private long shippingFeeBeforeDiscount;
    @Column(name = "shipping_discount_amount", nullable = false)
    private long shippingDiscountAmount;
    @Column(name = "shipping_fee", nullable = false)
    private long shippingFee;
    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 32)
    private CheckoutGroupPaymentStatus paymentStatus = CheckoutGroupPaymentStatus.PENDING;
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 24)
    private CheckoutGroupLifecycleStatus lifecycleStatus = CheckoutGroupLifecycleStatus.PENDING;
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 32)
    private CheckoutGroupFulfillmentStatus fulfillmentStatus = CheckoutGroupFulfillmentStatus.PENDING;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "policy_version", nullable = false)
    private int policyVersion;
    @Column(name = "selection_revision", nullable = false, length = 128)
    private String selectionRevision;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 120)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;
    @Column(name = "request_hash_version", nullable = false, updatable = false)
    private int requestHashVersion = 1;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_recipient_name", length = 4096)
    private String shippingRecipientName;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_phone", length = 4096)
    private String shippingPhone;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_address", length = 4096)
    private String shippingAddress;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_ward", length = 4096)
    private String shippingWard;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_district", length = 4096)
    private String shippingDistrict;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_province", length = 4096)
    private String shippingProvince;
    @Column(name = "shipping_province_code", length = 20)
    private String shippingProvinceCode;
    @Column(name = "shipping_province_name", length = 255)
    private String shippingProvinceName;
    @Column(name = "shipping_ward_code", length = 20)
    private String shippingWardCode;
    @Column(name = "shipping_ward_name", length = 255)
    private String shippingWardName;
    @Column(name = "shipping_address_catalog_source", nullable = false, length = 32)
    private String shippingAddressCatalogSource = "LOCAL";

    @Version
    @Column(name = "version", nullable = false)
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CheckoutGroupEntity() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getGroupNumber() { return groupNumber; }
    public void setGroupNumber(String value) { groupNumber = value; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID value) { customerId = value; }
    public UUID getShipInfoId() { return shipInfoId; }
    public void setShipInfoId(UUID value) { shipInfoId = value; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String value) { paymentMethod = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public long getSubtotal() { return subtotal; }
    public void setSubtotal(long value) { subtotal = value; }
    public long getProductDiscountAmount() { return productDiscountAmount; }
    public void setProductDiscountAmount(long value) { productDiscountAmount = value; }
    public long getVoucherDiscountAmount() { return voucherDiscountAmount; }
    public void setVoucherDiscountAmount(long value) { voucherDiscountAmount = value; }
    public long getShippingFeeBeforeDiscount() { return shippingFeeBeforeDiscount; }
    public void setShippingFeeBeforeDiscount(long value) { shippingFeeBeforeDiscount = value; }
    public long getShippingDiscountAmount() { return shippingDiscountAmount; }
    public void setShippingDiscountAmount(long value) { shippingDiscountAmount = value; }
    public long getShippingFee() { return shippingFee; }
    public void setShippingFee(long value) { shippingFee = value; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long value) { totalAmount = value; }
    public CheckoutGroupPaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(CheckoutGroupPaymentStatus value) { paymentStatus = value; }
    public CheckoutGroupLifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(CheckoutGroupLifecycleStatus value) { lifecycleStatus = value; }
    public CheckoutGroupFulfillmentStatus getFulfillmentStatus() { return fulfillmentStatus; }
    public void setFulfillmentStatus(CheckoutGroupFulfillmentStatus value) { fulfillmentStatus = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public int getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(int value) { policyVersion = value; }
    public String getSelectionRevision() { return selectionRevision; }
    public void setSelectionRevision(String value) { selectionRevision = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public int getRequestHashVersion() { return requestHashVersion; }
    public void setRequestHashVersion(int value) { requestHashVersion = value; }
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
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CheckoutGroupEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
