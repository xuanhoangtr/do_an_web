package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ReturnCaseStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReturnCaseResponse(UUID id, String caseNumber, UUID orderId, UUID fulfillmentId,
                                 ReturnCaseStatus status, String reason, Instant requestDeadline,
                                 Instant shipDeadline, Instant disputeDeadline, List<Item> items) {
    public record Item(UUID orderItemId, int requestedQuantity, int approvedQuantity,
                       int receivedQuantity, int resolvedQuantity, long refundAmount) {}
}