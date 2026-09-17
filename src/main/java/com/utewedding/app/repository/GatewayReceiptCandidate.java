package com.utewedding.app.repository;

import com.utewedding.app.domain.PaymentProviderEnvironment;
import java.time.Instant;
import java.util.UUID;

/** A captured gateway payment that has not yet been reconciled to platform funds. */
public record GatewayReceiptCandidate(UUID paymentId, UUID orderId, String orderNumber, UUID shopId,
                                      String provider, String providerTransactionId, long amount,
                                      Instant capturedAt, PaymentProviderEnvironment providerEnvironment) {
}
