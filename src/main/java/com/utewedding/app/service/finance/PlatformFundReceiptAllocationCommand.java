package com.utewedding.app.service.finance;

import com.utewedding.app.domain.PlatformFundReceiptSourceKind;
import java.util.UUID;

public record PlatformFundReceiptAllocationCommand(
        String allocationKey,
        PlatformFundReceiptSourceKind sourceKind,
        String sourceReference,
        UUID shopId,
        UUID orderId,
        UUID fulfillmentId,
        long grossAmount,
        long feeAmount,
        long deductionAmount,
        long netAmount) {
}
