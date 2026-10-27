package com.utewedding.app.service.port;

import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherType;
import java.util.UUID;
import java.util.Collection;
import java.util.Map;

/** Pricing policy is injected so campaigns can move out of business services. */
public interface DiscountPolicy {
    DiscountResult calculate(String discountCode, long eligibleAmount);

    default DiscountResult calculateForUser(String discountCode, long eligibleAmount, UUID userId) {
        return calculate(discountCode, eligibleAmount);
    }

    default DiscountResult previewForUser(String discountCode, long eligibleAmount, UUID userId) {
        return calculate(discountCode, eligibleAmount);
    }

    default DiscountResult calculateForCheckout(String discountCode, long eligibleAmount,
                                                long customerShippingFee, String shippingProviderCode,
                                                UUID userId) {
        return calculateForUser(discountCode, eligibleAmount, userId);
    }

    default DiscountResult previewForCheckout(String discountCode, long eligibleAmount,
                                              long customerShippingFee, String shippingProviderCode,
                                              UUID userId) {
        return previewForUser(discountCode, eligibleAmount, userId);
    }

    /** Calculates the independent merchandise and shipping voucher slots. A legacy single code may still be either type. */
    default CheckoutDiscounts calculateCheckoutSlots(String merchandiseCode, String shippingCode,
                                                       long eligibleAmount, long customerShippingFee,
                                                       String shippingProviderCode, UUID userId) {
        return calculateCheckoutSlots(merchandiseCode, shippingCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, null);
    }

    /** The shop ID must come from server-side cart products, never the voucher request. */
    default CheckoutDiscounts calculateCheckoutSlots(String merchandiseCode, String shippingCode,
                                                       long eligibleAmount, long customerShippingFee,
                                                       String shippingProviderCode, UUID userId, UUID selectedShopId) {
        DiscountResult merchandise = merchandiseCode == null || merchandiseCode.isBlank()
                ? DiscountResult.none()
                : calculateForCheckout(merchandiseCode, eligibleAmount, customerShippingFee,
                        shippingProviderCode, userId);
        DiscountResult shipping = shippingCode == null || shippingCode.isBlank()
                ? DiscountResult.none()
                : calculateForCheckout(shippingCode, eligibleAmount, customerShippingFee,
                        shippingProviderCode, userId);
        return classifySlots(merchandiseCode, shippingCode, merchandise, shipping);
    }

    /** Read-only counterpart to {@link #calculateCheckoutSlots}. */
    default CheckoutDiscounts previewCheckoutSlots(String merchandiseCode, String shippingCode,
                                                    long eligibleAmount, long customerShippingFee,
                                                    String shippingProviderCode, UUID userId) {
        return previewCheckoutSlots(merchandiseCode, shippingCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, null);
    }

    /** The shop ID must come from server-side cart products, never the voucher request. */
    default CheckoutDiscounts previewCheckoutSlots(String merchandiseCode, String shippingCode,
                                                    long eligibleAmount, long customerShippingFee,
                                                    String shippingProviderCode, UUID userId, UUID selectedShopId) {
        DiscountResult merchandise = merchandiseCode == null || merchandiseCode.isBlank()
                ? DiscountResult.none()
                : previewForCheckout(merchandiseCode, eligibleAmount, customerShippingFee,
                        shippingProviderCode, userId);
        DiscountResult shipping = shippingCode == null || shippingCode.isBlank()
                ? DiscountResult.none()
                : previewForCheckout(shippingCode, eligibleAmount, customerShippingFee,
                        shippingProviderCode, userId);
        return classifySlots(merchandiseCode, shippingCode, merchandise, shipping);
    }

    private static CheckoutDiscounts classifySlots(String merchandiseCode, String shippingCode,
                                                    DiscountResult merchandise, DiscountResult shipping) {
        boolean explicitShipping = shippingCode != null && !shippingCode.isBlank();
        if (merchandise.type() != null && merchandise.type().isShippingVoucher()) {
            if (explicitShipping) {
                throw new com.utewedding.app.exception.BusinessConflictException(
                        "Hãy nhập mã vận chuyển vào ô mã vận chuyển.");
            }
            shipping = merchandise;
            merchandise = DiscountResult.none();
        }
        if (shipping.type() != null && !shipping.type().isShippingVoucher()) {
            throw new com.utewedding.app.exception.BusinessConflictException(
                    "Ô mã vận chuyển chỉ nhận voucher giảm phí vận chuyển.");
        }
        if (merchandise.type() != null && merchandise.type().isShippingVoucher()) {
            throw new com.utewedding.app.exception.BusinessConflictException(
                    "Ô mã tiền hàng chỉ nhận voucher giảm tiền hàng.");
        }
        if (merchandise.appliedCode() != null && shipping.appliedCode() != null
                && merchandise.appliedCode().equalsIgnoreCase(shipping.appliedCode())) {
            throw new com.utewedding.app.exception.BusinessConflictException(
                    "Một voucher không thể dùng cho cả tiền hàng và phí vận chuyển.");
        }
        return new CheckoutDiscounts(merchandise, shipping);
    }

    default void reserve(String discountCode, UUID userId, UUID orderId, long discountAmount) {
        // Legacy/focused policies do not persist usage reservations.
    }

    /** Validates ownership against the server-resolved shop before reserving usage. */
    default void reserve(String discountCode, UUID userId, UUID orderId, long discountAmount,
                         UUID selectedShopId) {
        reserve(discountCode, userId, orderId, discountAmount);
    }

    default void release(UUID orderId) {
        // Legacy/focused policies do not persist usage reservations.
    }

    /** Acquires all voucher definition locks in canonical code order before grouped checkout validation. */
    default void lockForCheckout(Collection<String> codes) {
        // Focused and legacy policies may not use database-backed voucher rows.
    }

    /**
     * Validates the number of uses planned for each voucher across one grouped checkout.
     * Implementations with persisted quotas should check both global and per-user limits.
     * The caller acquires voucher locks first when {@code lock} is true.
     */
    default Map<String, String> validateBatchUsage(UUID userId, Map<String, Integer> requestedUses,
                                                   boolean lock) {
        return Map.of();
    }

    record DiscountResult(long amount, String appliedCode, VoucherType type, UUID voucherId,
                          VoucherScope scope, VoucherFundingSource fundingSource) {
        public DiscountResult(long amount, String appliedCode, VoucherType type) {
            this(amount, appliedCode, type, null, null, null);
        }
        public DiscountResult(long amount, String appliedCode) {
            this(amount, appliedCode, appliedCode == null ? null : VoucherType.FIXED);
        }
        public static DiscountResult none() { return new DiscountResult(0, null, null, null, null, null); }
    }

    record CheckoutDiscounts(DiscountResult merchandise, DiscountResult shipping) {
        public CheckoutDiscounts {
            merchandise = merchandise == null ? DiscountResult.none() : merchandise;
            shipping = shipping == null ? DiscountResult.none() : shipping;
        }
        public long totalBenefit() { return Math.addExact(merchandise.amount(), shipping.amount()); }
        public String legacyCode() {
            return merchandise.appliedCode() != null ? merchandise.appliedCode() : shipping.appliedCode();
        }
        public VoucherType legacyType() {
            return merchandise.type() != null ? merchandise.type() : shipping.type();
        }
    }
}
