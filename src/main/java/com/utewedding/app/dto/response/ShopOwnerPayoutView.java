package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ShopPayoutStatus;
import com.utewedding.app.domain.entity.ShopPayoutEntity;
import java.time.Instant;
import java.util.UUID;

/** Owner-safe payout summary; excludes private proof identifiers and Admin identities. */
public record ShopOwnerPayoutView(UUID id, UUID settlementPeriodId, long amount, String currency,
                                  long destinationVersion, String bankCode, String maskedAccountNumber,
                                  String accountHolderName, ShopPayoutStatus status,
                                  Instant requestedAt, Instant approvedAt, Instant processingAt, Instant paidAt) {
    public static ShopOwnerPayoutView from(ShopPayoutEntity entity) {
        return new ShopOwnerPayoutView(entity.getId(), entity.getSettlementPeriodId(), entity.getAmount(),
                entity.getCurrency(), entity.getDestinationVersion(), entity.getDestinationBankCodeSnapshot(),
                ShopPayoutDestinationView.mask(entity.getDestinationAccountNumberSnapshot()),
                entity.getDestinationAccountHolderSnapshot(), entity.getStatus(), entity.getRequestedAt(),
                entity.getApprovedAt(), entity.getProcessingAt(), entity.getPaidAt());
    }
}
