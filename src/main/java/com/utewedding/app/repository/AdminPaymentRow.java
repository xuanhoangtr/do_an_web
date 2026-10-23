package com.utewedding.app.repository;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminPaymentRow(UUID paymentId, UUID orderId, String orderNumber,
                              Instant orderCreatedAt, String paymentMethod, String provider,
                              long amount, String transactionId, Instant paymentCreatedAt,
                              PaymentStatus paymentStatus,
                              OrderStatus orderStatus) {
}
