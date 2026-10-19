package com.utewedding.app.dto.response;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public interface VendorOrderSummary {
    UUID getId();
    String getOrderNumber();
    OrderStatus getStatus();
    PaymentStatus getPaymentStatus();
    String getPaymentMethod();
    Instant getCreatedAt();
}
