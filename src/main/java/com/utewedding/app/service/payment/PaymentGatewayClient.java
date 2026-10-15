package com.utewedding.app.service.payment;

/** Provider refund and transaction-query operations. Callback verification stays in the pay adapters. */
public interface PaymentGatewayClient {
    String CAPTURED = "CAPTURED";
    String PENDING = "PENDING";
    String FAILED = "FAILED";
    String NOT_FOUND = "NOT_FOUND";
    String REFUND_PENDING = "REFUND_PENDING";
    String REFUND_REJECTED = "REFUND_REJECTED";
    String UNKNOWN = "UNKNOWN";
    String QUERY_FAILED = "QUERY_FAILED";

    String providerCode();

    RefundResult refund(RefundCommand command);

    QueryResult query(QueryCommand command);

    record RefundCommand(
            String merchantReference,
            String providerTransactionId,
            String providerCreateDate,
            long amount,
            long originalAmount,
            boolean partial,
            String idempotencyKey,
            String actor,
            String clientIp,
            String description) {
    }

    record RefundResult(
            boolean success,
            boolean duplicateAtProvider,
            String responseCode,
            String message,
            String providerTransactionId,
            String rawBody) {
    }

    record QueryCommand(String merchantReference, String providerCreateDate, String clientIp) {
    }

    record QueryResult(
            String providerState,
            String providerTransactionId,
            Long amount,
            String responseCode,
            String message,
            String rawBody) {
    }
}
