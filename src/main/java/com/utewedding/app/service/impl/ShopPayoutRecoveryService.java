package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopPayoutStatus;
import com.utewedding.app.domain.entity.ShopPayoutRecoveryDebtEntity;
import com.utewedding.app.domain.entity.ShopSettlementEntryEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.ShopPayoutRecoveryDebtRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links a seller refund debit to a payout that has crossed the potentially-paid boundary.
 * It does not append another ledger debit; the immutable REFUND entry remains the debt source.
 */
@Service
public class ShopPayoutRecoveryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ShopPayoutRecoveryDebtRepository recoveries;
    private final Clock clock;

    public ShopPayoutRecoveryService(NamedParameterJdbcTemplate jdbc,
                                    ShopPayoutRecoveryDebtRepository recoveries,
                                    Clock shopSettlementClock) {
        this.jdbc = jdbc;
        this.recoveries = recoveries;
        this.clock = shopSettlementClock;
    }

    /** Must be called in the existing transaction while its shop finance anchor is locked. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recordRefundRecovery(ShopSettlementEntryEntity refundEntry) {
        if (refundEntry == null || refundEntry.getId() == null
                || refundEntry.getEntryType() != ShopSettlementEntryEntity.Type.REFUND
                || refundEntry.getDirection() != ShopSettlementEntryEntity.Direction.DEBIT
                || refundEntry.getAmount() <= 0 || refundEntry.getOrderId() == null) {
            return;
        }

        ShopPayoutRecoveryDebtEntity old = recoveries.findByRefundEntryId(refundEntry.getId()).orElse(null);
        if (old != null) {
            requireSame(old, refundEntry);
            return;
        }

        List<UUID> payoutIds = jdbc.query("""
                SELECT payout.id
                FROM shop_settlement_entries sale
                JOIN shop_settlement_period_entries assignment
                  ON assignment.settlement_entry_id = sale.id AND assignment.shop_id = sale.shop_id
                JOIN shop_payouts payout
                  ON payout.settlement_period_id = assignment.period_id AND payout.shop_id = sale.shop_id
                WHERE sale.shop_id = :shopId AND sale.order_id = :orderId
                  AND sale.entry_type = 'SALE' AND sale.direction = 'CREDIT' AND sale.amount > 0
                  AND payout.status IN ('PROCESSING', 'PAID')
                ORDER BY payout.created_at DESC, payout.id
                LIMIT 1
                FOR UPDATE OF payout
                """, Map.of("shopId", refundEntry.getShopId(), "orderId", refundEntry.getOrderId()),
                (rs, row) -> rs.getObject(1, UUID.class));
        if (payoutIds.isEmpty()) return;

        recoveries.saveAndFlush(new ShopPayoutRecoveryDebtEntity(refundEntry.getShopId(), payoutIds.get(0),
                refundEntry.getId(), refundEntry.getAmount(), clock.instant()));
    }

    private static void requireSame(ShopPayoutRecoveryDebtEntity old, ShopSettlementEntryEntity source) {
        if (!old.getShopId().equals(source.getShopId()) || old.getAmount() != source.getAmount()) {
            throw new BusinessConflictException("Khoản thu hồi hiện có không khớp bút toán refund gốc.");
        }
    }
}
