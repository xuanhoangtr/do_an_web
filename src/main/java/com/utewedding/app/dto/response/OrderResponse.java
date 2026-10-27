package com.utewedding.app.dto.response;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.OverallFulfillmentStatus;
import com.utewedding.app.domain.VoucherType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        long subtotal,
        long discountAmount,
        long totalAmount,
        String currency,
        UUID weddingId,
        Instant paidAt,
        Instant cancelledAt,
        Instant createdAt,
        List<OrderItemResponse> items,
        List<PaymentResponse> payments,
        PaymentMethod paymentMethod,
        ShippingAddressResponse shipping,
        long shippingFee,
        Instant expiresAt,
        PaymentStatus paymentStatus,
        List<FulfillmentResponse> fulfillments,
        OverallFulfillmentStatus overallFulfillmentStatus,
        String discountCode,
        Instant confirmedAt,
        long shippingFeeBeforeDiscount,
        long shippingDiscountAmount,
        long voucherDiscountAmount,
        VoucherType voucherType,
        String shopNameSnapshot,
        List<AppliedVoucherResponse> appliedVouchers) {

    /** Compatibility constructor for the pre two-slot response shape. */
    public OrderResponse(UUID id, String orderNumber, OrderStatus status, long subtotal,
                         long discountAmount, long totalAmount, String currency, UUID weddingId,
                         Instant paidAt, Instant cancelledAt, Instant createdAt,
                         List<OrderItemResponse> items, List<PaymentResponse> payments,
                         PaymentMethod paymentMethod, ShippingAddressResponse shipping,
                         long shippingFee, Instant expiresAt, PaymentStatus paymentStatus,
                         List<FulfillmentResponse> fulfillments,
                         OverallFulfillmentStatus overallFulfillmentStatus, String discountCode,
                         Instant confirmedAt, long shippingFeeBeforeDiscount,
                         long shippingDiscountAmount, long voucherDiscountAmount,
                         VoucherType voucherType, String shopNameSnapshot) {
        this(id, orderNumber, status, subtotal, discountAmount, totalAmount, currency, weddingId,
                paidAt, cancelledAt, createdAt, items, payments, paymentMethod, shipping, shippingFee,
                expiresAt, paymentStatus, fulfillments, overallFulfillmentStatus, discountCode,
                confirmedAt, shippingFeeBeforeDiscount, shippingDiscountAmount, voucherDiscountAmount,
                voucherType, shopNameSnapshot, List.of());
    }

    public OrderResponse {
        appliedVouchers = appliedVouchers == null ? List.of() : List.copyOf(appliedVouchers);
    }

    public OrderResponse(UUID id, String orderNumber, OrderStatus status, long subtotal,
                         long discountAmount, long totalAmount, String currency, UUID weddingId,
                         Instant paidAt, Instant cancelledAt, Instant createdAt,
                         List<OrderItemResponse> items, List<PaymentResponse> payments,
                         PaymentMethod paymentMethod, ShippingAddressResponse shipping,
                         long shippingFee, Instant expiresAt, PaymentStatus paymentStatus,
                         List<FulfillmentResponse> fulfillments,
                         OverallFulfillmentStatus overallFulfillmentStatus, String discountCode,
                         Instant confirmedAt) {
        this(id, orderNumber, status, subtotal, discountAmount, totalAmount, currency, weddingId,
                paidAt, cancelledAt, createdAt, items, payments, paymentMethod, shipping,
                shippingFee, expiresAt, paymentStatus, fulfillments, overallFulfillmentStatus,
                discountCode, confirmedAt, shippingFee, 0, 0, null, null);
    }

    public OrderResponse(UUID id, String orderNumber, OrderStatus status, long subtotal,
                         long discountAmount, long totalAmount, String currency, UUID weddingId,
                         Instant paidAt, Instant cancelledAt, Instant createdAt,
                         List<OrderItemResponse> items, List<PaymentResponse> payments) {
        this(id, orderNumber, status, subtotal, discountAmount, totalAmount, currency, weddingId,
                paidAt, cancelledAt, createdAt, items, payments, null, null, 0, null,
                null, List.of(), OverallFulfillmentStatus.NOT_READY, null, null, 0, 0, 0, null, null);
    }

    public OrderResponse(UUID id, String orderNumber, OrderStatus status, long subtotal,
                         long discountAmount, long totalAmount, String currency, UUID weddingId,
                         Instant paidAt, Instant cancelledAt, Instant createdAt,
                         List<OrderItemResponse> items, List<PaymentResponse> payments,
                         PaymentMethod paymentMethod, ShippingAddressResponse shipping,
                         long shippingFee, Instant expiresAt) {
        this(id, orderNumber, status, subtotal, discountAmount, totalAmount, currency, weddingId,
                paidAt, cancelledAt, createdAt, items, payments, paymentMethod, shipping,
                shippingFee, expiresAt, null, List.of(), OverallFulfillmentStatus.NOT_READY, null, null,
                shippingFee, 0, 0, null, null);
    }

    /** Compatibility overload for callers using the pre-confirmation response shape. */
    public OrderResponse(UUID id, String orderNumber, OrderStatus status, long subtotal,
                         long discountAmount, long totalAmount, String currency, UUID weddingId,
                         Instant paidAt, Instant cancelledAt, Instant createdAt,
                         List<OrderItemResponse> items, List<PaymentResponse> payments,
                         PaymentMethod paymentMethod, ShippingAddressResponse shipping,
                         long shippingFee, Instant expiresAt, PaymentStatus paymentStatus,
                         List<FulfillmentResponse> fulfillments,
                         OverallFulfillmentStatus overallFulfillmentStatus, String discountCode) {
        this(id, orderNumber, status, subtotal, discountAmount, totalAmount, currency, weddingId,
                paidAt, cancelledAt, createdAt, items, payments, paymentMethod, shipping,
                shippingFee, expiresAt, paymentStatus, fulfillments, overallFulfillmentStatus,
                discountCode, null);
    }

    /** Thymeleaf view contract uses the concise {@code order.total} property. */
    public long getTotal() {
        return totalAmount;
    }

    public List<AppliedVoucherResponse> getAppliedVouchers() { return appliedVouchers; }
}
