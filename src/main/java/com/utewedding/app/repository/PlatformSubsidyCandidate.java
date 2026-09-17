package com.utewedding.app.repository;

import java.util.UUID;

/** Immutable platform-funded merchandise discount attributed to an order/shop. */
public record PlatformSubsidyCandidate(UUID orderId, String orderNumber, UUID shopId, long subsidyAmount) {
}
