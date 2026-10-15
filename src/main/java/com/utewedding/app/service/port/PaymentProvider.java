package com.utewedding.app.service.port;

import java.util.Map;
import java.util.UUID;

public interface PaymentProvider {
    PaymentRequest create(UUID userId, UUID orderId);
    PaymentResult callback(Map<String, String> parameters);
    record PaymentRequest(String provider, String orderId, long amount, String signature, String endpoint) { }
    record PaymentResult(boolean accepted, String code, String message) { }
}
