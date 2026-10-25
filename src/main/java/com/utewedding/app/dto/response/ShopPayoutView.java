package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ShopPayoutStatus;
import com.utewedding.app.domain.entity.ShopPayoutEntity;
import java.time.Instant;
import java.util.UUID;

public record ShopPayoutView(UUID id, UUID settlementPeriodId, long amount, String currency,
                            UUID destinationId, long destinationVersion, String bankCode,
                            String maskedAccountNumber, String accountHolderName, ShopPayoutStatus status,
                            UUID requestedBy, Instant requestedAt, UUID approvedBy, Instant approvedAt,
                            UUID processingBy, Instant processingAt, UUID paidBy, Instant paidAt,
                            String providerReference, UUID proofMediaId, String note) {
    public static ShopPayoutView from(ShopPayoutEntity entity) {
        return new ShopPayoutView(entity.getId(), entity.getSettlementPeriodId(), entity.getAmount(), entity.getCurrency(),
                entity.getDestinationId(), entity.getDestinationVersion(), entity.getDestinationBankCodeSnapshot(),
                ShopPayoutDestinationView.mask(entity.getDestinationAccountNumberSnapshot()),
                entity.getDestinationAccountHolderSnapshot(), entity.getStatus(), entity.getRequestedBy(),
                entity.getRequestedAt(), entity.getApprovedBy(), entity.getApprovedAt(), entity.getProcessingBy(),
                entity.getProcessingAt(), entity.getPaidBy(), entity.getPaidAt(), entity.getProviderReference(),
                entity.getProofMediaId(), entity.getNote());
    }
}
