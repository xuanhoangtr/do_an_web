package com.utewedding.app.dto.response;

import java.util.List;
import java.util.UUID;
import com.utewedding.app.domain.VoucherType;

public record CheckoutPricingPreview(
        long subtotal,
        long productDiscountAmount,
        long voucherDiscountAmount,
        long shippingFee,
        long totalAmount,
        String appliedVoucherCode,
        List<Line> lines,
        long shippingFeeBeforeDiscount,
        long shippingDiscountAmount,
        VoucherType voucherType,
        String appliedMerchandiseVoucherCode,
        VoucherType merchandiseVoucherType,
        String appliedShippingVoucherCode,
        VoucherType shippingVoucherType) {

    public CheckoutPricingPreview(long subtotal, long productDiscountAmount, long voucherDiscountAmount,
                                  long shippingFee, long totalAmount, String appliedVoucherCode,
                                  List<Line> lines, long shippingFeeBeforeDiscount,
                                  long shippingDiscountAmount, VoucherType voucherType) {
        this(subtotal, productDiscountAmount, voucherDiscountAmount, shippingFee, totalAmount,
                appliedVoucherCode, lines, shippingFeeBeforeDiscount, shippingDiscountAmount,
                voucherType,
                voucherType != null && voucherType.isShippingVoucher() ? null : appliedVoucherCode,
                voucherType != null && voucherType.isShippingVoucher() ? null : voucherType,
                voucherType != null && voucherType.isShippingVoucher() ? appliedVoucherCode : null,
                voucherType != null && voucherType.isShippingVoucher() ? voucherType : null);
    }

    public CheckoutPricingPreview(long subtotal, long productDiscountAmount, long voucherDiscountAmount,
                                  long shippingFee, long totalAmount, String appliedVoucherCode,
                                  List<Line> lines) {
        this(subtotal, productDiscountAmount, voucherDiscountAmount, shippingFee, totalAmount,
                appliedVoucherCode, lines, shippingFee, 0, null);
    }

    public CheckoutPricingPreview {
        lines = List.copyOf(lines);
    }

    public long getSubtotal() { return subtotal; }
    public long getProductDiscountAmount() { return productDiscountAmount; }
    public long getVoucherDiscountAmount() { return voucherDiscountAmount; }
    public long getShippingFee() { return shippingFee; }
    public long getShippingFeeBeforeDiscount() { return shippingFeeBeforeDiscount; }
    public long getShippingDiscountAmount() { return shippingDiscountAmount; }
    public VoucherType getVoucherType() { return voucherType; }
    public String getAppliedMerchandiseVoucherCode() { return appliedMerchandiseVoucherCode; }
    public VoucherType getMerchandiseVoucherType() { return merchandiseVoucherType; }
    public String getAppliedShippingVoucherCode() { return appliedShippingVoucherCode; }
    public VoucherType getShippingVoucherType() { return shippingVoucherType; }
    public long getVoucherBenefitAmount() { return Math.addExact(voucherDiscountAmount, shippingDiscountAmount); }
    public long getTotalAmount() { return totalAmount; }
    public String getAppliedVoucherCode() { return appliedVoucherCode; }
    public List<Line> getLines() { return lines; }

    public record Line(UUID productId, String sku, long unitPrice, int quantity,
                       int discountPercent, long discountAmount, long lineTotal) {
    }
}
