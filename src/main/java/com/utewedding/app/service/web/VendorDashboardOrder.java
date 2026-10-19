package com.utewedding.app.service.web;

import com.utewedding.app.domain.OrderStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Minimal recent-order row without buyer contact or address data. */
public record VendorDashboardOrder(UUID id, String orderNumber, OrderStatus status, Instant createdAt) {
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    public String createdAtLabel() { return createdAt == null ? "" : LOCAL_DATE_TIME.format(createdAt); }
}
