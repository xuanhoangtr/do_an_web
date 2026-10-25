package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ShopSettlementPeriodStatus;
import com.utewedding.app.domain.entity.ShopSettlementPeriodEntity;
import java.time.Instant;
import java.util.UUID;

public record ShopSettlementPeriodResponse(
        UUID id,
        UUID shopId,
        Instant periodStartAt,
        Instant periodEndAt,
        String timezone,
        String currency,
        ShopSettlementPeriodStatus status,
        long creditAmount,
        long debitAmount,
        long recoveryDebitAmount,
        long netAmount,
        int entryCount,
        long version,
        Instant approvedAt,
        Instant processingAt,
        Instant paidAt,
        boolean recalculationRequired) {

    public static ShopSettlementPeriodResponse from(ShopSettlementPeriodEntity period) {
        return new ShopSettlementPeriodResponse(period.getId(), period.getShopId(), period.getPeriodStartAt(),
                period.getPeriodEndAt(), period.getTimezone(), period.getCurrency(), period.getStatus(),
                period.getCreditAmount(), period.getDebitAmount(), period.getRecoveryDebitAmount(),
                period.getNetAmount(), period.getEntryCount(),
                period.getVersion(), period.getApprovedAt(), period.getProcessingAt(), period.getPaidAt(),
                period.getStatus() == ShopSettlementPeriodStatus.DRAFT && period.getLastRecalculationId() != null);
    }
}
