package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopSettlementPeriodEntity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Source-state checks and idempotent assignment writes for settlement periods. */
@Repository
public class ShopSettlementEligibilityRepository {
    public record RecoveryOffsetTotals(long amount, int count) { }

    private static final String ELIGIBLE_CREDIT = """
            e.direction = 'CREDIT'
            AND e.order_id IS NOT NULL
            AND e.amount > 0
            AND o.id IS NOT NULL
            AND snapshot.id IS NOT NULL
            AND o.status IN ('PAID', 'REFUNDED')
            AND o.payment_status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
            AND EXISTS (
                SELECT 1 FROM order_fulfillments delivered
                WHERE delivered.order_id = o.id AND delivered.shop_id = e.shop_id
                  AND delivered.status = 'DELIVERED' AND delivered.delivered_at IS NOT NULL
            )
            AND NOT EXISTS (
                SELECT 1 FROM order_fulfillments pending_fulfillment
                WHERE pending_fulfillment.order_id = o.id AND pending_fulfillment.shop_id = e.shop_id
                  AND pending_fulfillment.status NOT IN ('DELIVERED', 'CANCELLED')
            )
            AND NOT EXISTS (
                SELECT 1 FROM order_fulfillments missing_delivery_time
                WHERE missing_delivery_time.order_id = o.id AND missing_delivery_time.shop_id = e.shop_id
                  AND missing_delivery_time.status = 'DELIVERED' AND missing_delivery_time.delivered_at IS NULL
            )
            AND :now >= (
                SELECT max(last_delivery.delivered_at)
                         + (snapshot.return_window_days_snapshot * INTERVAL '24 hours')
                FROM order_fulfillments last_delivery
                WHERE last_delivery.order_id = o.id AND last_delivery.shop_id = e.shop_id
                  AND last_delivery.status = 'DELIVERED' AND last_delivery.delivered_at IS NOT NULL
            )
            AND funds.receipt_count > 0
            AND (NOT cod_policy.requires_carrier_remittance
                 OR funds.carrier_fulfillment_count = (
                     SELECT count(*) FROM order_fulfillments delivered_cod
                     WHERE delivered_cod.order_id = o.id AND delivered_cod.shop_id = e.shop_id
                       AND delivered_cod.status = 'DELIVERED'
                 ))
            AND ((subsidy.required_amount = 0 AND subsidy.allocation_count = 0)
                 OR (subsidy.required_amount > 0 AND subsidy.allocation_count = 1
                     AND subsidy.received_amount = subsidy.required_amount))
            AND funds.net_received + subsidy.received_amount >= e.amount
            AND NOT EXISTS (
                SELECT 1 FROM return_cases return_case
                WHERE return_case.order_id = o.id AND return_case.shop_id = e.shop_id
                  AND return_case.status NOT IN ('CLOSED', 'CANCELLED', 'EXPIRED')
            )
            AND NOT EXISTS (
                SELECT 1 FROM return_disputes dispute
                JOIN return_cases dispute_case ON dispute_case.id = dispute.return_case_id
                WHERE dispute_case.order_id = o.id AND dispute_case.shop_id = e.shop_id
                  AND dispute.status = 'OPEN'
            )
            AND NOT EXISTS (
                SELECT 1 FROM shop_finance_holds finance_hold
                WHERE finance_hold.shop_id = e.shop_id AND finance_hold.order_id = o.id
                  AND finance_hold.status = 'ACTIVE'
            )
            """;

    private static final String SOURCE_JOINS = """
            FROM shop_settlement_entries e
            LEFT JOIN orders o ON o.id = e.order_id AND o.shop_id = e.shop_id
            LEFT JOIN shop_order_financial_snapshots snapshot
                   ON snapshot.order_id = e.order_id AND snapshot.shop_id = e.shop_id
            LEFT JOIN LATERAL (
                SELECT upper(o.payment_method) = 'COD'
                       AND EXISTS (
                           SELECT 1 FROM shops finance_shop
                           JOIN order_shipping_snapshots finance_shipping
                             ON finance_shipping.order_id = o.id
                           WHERE finance_shop.id = o.shop_id AND finance_shop.kind = 'VENDOR'
                             AND upper(btrim(finance_shipping.provider_code)) = 'GHN'
                       ) AS requires_carrier_remittance
            ) cod_policy ON true
            LEFT JOIN LATERAL (
                SELECT coalesce(sum(receipt_allocation.net_amount), 0) AS net_received,
                       count(*) AS receipt_count,
                       count(DISTINCT receipt_allocation.fulfillment_id) AS carrier_fulfillment_count
                FROM platform_fund_receipt_allocations receipt_allocation
                JOIN platform_fund_receipts receipt ON receipt.id = receipt_allocation.receipt_id
                WHERE receipt_allocation.shop_id = e.shop_id
                  AND (receipt_allocation.order_id = o.id OR EXISTS (
                       SELECT 1 FROM order_fulfillments receipt_fulfillment
                       WHERE receipt_fulfillment.id = receipt_allocation.fulfillment_id
                         AND receipt_fulfillment.order_id = o.id
                         AND receipt_fulfillment.shop_id = e.shop_id
                  ))
                  AND receipt.received_at <= :now
                  AND receipt.created_at <= :now
                  AND receipt.environment IN ('PRODUCTION', 'MANUAL', 'INTERNAL')
                  AND ((upper(o.payment_method) = 'COD' AND cod_policy.requires_carrier_remittance
                        AND receipt.source_code = 'CARRIER_REMITTANCE'
                        AND receipt_allocation.source_kind = 'CARRIER_REMITTANCE'
                        AND upper(btrim(receipt.provider_code)) = 'GHN'
                        AND receipt.environment = 'PRODUCTION'
                        AND receipt.external_reference IS NOT NULL
                        AND receipt.evidence_reference IS NOT NULL
                        AND receipt.evidence_note IS NOT NULL
                        AND receipt.gross_amount = receipt_allocation.gross_amount
                        AND receipt.fee_amount = receipt_allocation.fee_amount
                        AND receipt.deduction_amount = receipt_allocation.deduction_amount
                        AND receipt.net_amount = receipt_allocation.net_amount
                        AND EXISTS (
                            SELECT 1
                            FROM order_fulfillments carrier_fulfillment
                            JOIN fulfillment_shipments carrier_shipment
                              ON carrier_shipment.fulfillment_id = carrier_fulfillment.id
                            JOIN order_shipping_snapshots carrier_shipping
                              ON carrier_shipping.order_id = carrier_fulfillment.order_id
                            JOIN cod_obligations carrier_obligation
                              ON carrier_obligation.fulfillment_id = carrier_fulfillment.id
                            JOIN shipping_provider_accounts carrier_account
                              ON carrier_account.id = carrier_shipment.provider_account_id
                            JOIN shop_shipping_bindings carrier_binding
                              ON carrier_binding.id = (carrier_shipment.provider_context_snapshot ->> 'bindingId')::uuid
                             AND carrier_binding.shop_id = carrier_fulfillment.shop_id
                            WHERE carrier_fulfillment.id = receipt_allocation.fulfillment_id
                              AND carrier_fulfillment.order_id = o.id
                              AND carrier_fulfillment.shop_id = e.shop_id
                              AND carrier_fulfillment.status = 'DELIVERED'
                              AND carrier_shipment.shop_id = e.shop_id
                              AND upper(btrim(carrier_shipment.provider_code)) = 'GHN'
                              AND carrier_shipment.status = 'DELIVERED'
                              AND carrier_shipment.provider_shipment_id IS NOT NULL
                              AND carrier_shipment.provider_account_id IS NOT NULL
                              AND upper(btrim(carrier_shipping.provider_code)) = 'GHN'
                              AND carrier_shipment.provider_context_snapshot ->> 'shopId' = e.shop_id::text
                              AND carrier_shipment.provider_context_snapshot ->> 'accountId'
                                  = carrier_account.id::text
                              AND carrier_shipment.provider_context_snapshot ->> 'credentialReference'
                                  = carrier_account.credential_reference
                              AND (carrier_shipment.provider_context_snapshot ->> 'accountUpdatedAt')::timestamptz
                                  = carrier_account.updated_at
                              AND carrier_shipment.provider_context_snapshot ->> 'environment'
                                  = carrier_account.environment
                              AND upper(btrim(carrier_account.provider_code)) = 'GHN'
                              AND carrier_account.status = 'VERIFIED'
                              AND carrier_account.verified_at IS NOT NULL
                              AND carrier_account.environment = 'PRODUCTION'
                              AND carrier_binding.provider_account_id = carrier_account.id
                              AND carrier_binding.status = 'VERIFIED'
                              AND (
                                  (carrier_binding.route_mode = 'LEGACY_ROUTES'
                                      AND carrier_binding.route_coverage_verified)
                                  OR (carrier_binding.route_mode = 'API_QUOTE'
                                      AND carrier_shipping.pricing_mode = 'PROVIDER_QUOTE'
                                      AND carrier_shipping.shipping_binding_id = carrier_binding.id
                                      AND carrier_shipping.shipping_binding_revision = carrier_binding.revision
                                      AND carrier_shipping.shipping_provider_account_id = carrier_account.id
                                      AND carrier_shipping.shipping_provider_account_updated_at = carrier_account.updated_at
                                      AND (carrier_shipment.provider_context_snapshot ->> 'routeId')
                                          IS NOT DISTINCT FROM carrier_shipping.shipping_route_id::text)
                              )
                              AND carrier_binding.cod_beneficiary_verified
                              AND carrier_binding.cod_beneficiary_verified_at IS NOT NULL
                              AND carrier_binding.cod_beneficiary_evidence_redacted IS NOT NULL
                              AND (carrier_shipment.provider_context_snapshot ->> 'bindingRevision')::integer
                                  = carrier_binding.revision
                              AND carrier_obligation.status = 'COLLECTED'
                              AND carrier_obligation.actual_collected > 0
                              AND carrier_obligation.actual_collected = receipt_allocation.gross_amount
                              AND (carrier_shipment.provider_context_snapshot ->> 'expectedCodAmount')::bigint
                                  = carrier_obligation.expected_collectable
                              AND receipt.provider_account_reference = carrier_account.id::text
                              AND receipt.source_identity = 'carrier-remittance:' || carrier_shipment.id::text
                              AND receipt_allocation.source_reference = 'GHN:' || carrier_account.id::text
                                  || ':' || carrier_shipment.provider_shipment_id
                        ))
                    OR (upper(o.payment_method) = 'COD' AND NOT cod_policy.requires_carrier_remittance
                        AND EXISTS (
                            SELECT 1 FROM order_shipping_snapshots internal_shipping
                            WHERE internal_shipping.order_id = o.id
                              AND upper(btrim(internal_shipping.provider_code)) = 'INTERNAL'
                        )
                        AND receipt.source_code IN ('COD_REMITTANCE', 'CARRIER_REMITTANCE')
                        AND receipt_allocation.source_kind IN ('COD_REMITTANCE', 'CARRIER_REMITTANCE'))
                    OR (upper(o.payment_method) <> 'COD'
                        AND receipt.source_code IN ('GATEWAY_SETTLEMENT', 'MANUAL')
                        AND receipt_allocation.source_kind = 'ONLINE_PAYMENT'))
            ) funds ON true
            LEFT JOIN LATERAL (
                SELECT coalesce(sum(subsidy_allocation.net_amount), 0) AS received_amount,
                       count(subsidy_allocation.id) AS allocation_count,
                       coalesce((SELECT sum(discount.discount_amount)
                                 FROM order_applied_discounts discount
                                 WHERE discount.order_id = e.order_id AND discount.shop_id = e.shop_id
                                   AND discount.slot = 'MERCHANDISE'
                                   AND discount.funding_source = 'PLATFORM'), 0) AS required_amount
                FROM platform_fund_receipt_allocations subsidy_allocation
                JOIN platform_fund_receipts subsidy_receipt
                  ON subsidy_receipt.id = subsidy_allocation.receipt_id
                WHERE subsidy_allocation.shop_id = e.shop_id
                  AND subsidy_allocation.order_id = o.id
                  AND subsidy_allocation.allocation_key = 'order:' || o.id::text
                  AND subsidy_allocation.source_kind = 'OTHER'
                  AND subsidy_allocation.source_reference = 'PLATFORM_SUBSIDY'
                  AND subsidy_receipt.source_identity = 'platform-subsidy:' || o.id::text
                  AND subsidy_receipt.source_code = 'MANUAL'
                  AND subsidy_receipt.environment = 'MANUAL'
                  AND (subsidy_receipt.external_reference IS NOT NULL
                       OR subsidy_receipt.evidence_reference IS NOT NULL)
                  AND subsidy_receipt.received_at <= :now
                  AND subsidy_receipt.created_at <= :now
            ) subsidy ON true
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ShopSettlementEligibilityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Includes all shops with an unassigned source, regardless of its order or receipt week. */
    public List<UUID> findShopsWithUnassignedEntries(UUID platformShopId) {
        return jdbc.query("""
                SELECT candidate.shop_id FROM (
                    SELECT entry.shop_id
                    FROM shop_settlement_entries entry
                    WHERE entry.shop_id <> :platformShopId AND entry.amount > 0
                      AND NOT EXISTS (
                          SELECT 1 FROM shop_settlement_period_entries assigned
                          WHERE assigned.settlement_entry_id = entry.id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM shop_payout_recovery_debts recovery
                          WHERE recovery.refund_entry_id = entry.id AND recovery.shop_id = entry.shop_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM shop_settlement_period_entries sale_assignment
                          JOIN shop_settlement_periods approved_period
                            ON approved_period.id = sale_assignment.period_id
                           AND approved_period.shop_id = sale_assignment.shop_id
                           AND approved_period.status = 'APPROVED'
                          JOIN shop_settlement_entries sale
                            ON sale.id = sale_assignment.settlement_entry_id
                           AND sale.shop_id = sale_assignment.shop_id
                          WHERE entry.entry_type IN ('REFUND', 'ADJUSTMENT') AND entry.direction = 'DEBIT'
                            AND sale_assignment.entry_type = 'SALE'
                            AND sale.order_id = entry.order_id AND sale.shop_id = entry.shop_id
                      )
                    UNION
                    SELECT recovery.shop_id
                    FROM shop_payout_recovery_debts recovery
                    WHERE recovery.shop_id <> :platformShopId AND recovery.outstanding_amount > coalesce((
                        SELECT sum(reserved.amount) FROM shop_settlement_recovery_offsets reserved
                        WHERE reserved.recovery_debt_id = recovery.id
                          AND reserved.shop_id = recovery.shop_id AND reserved.status = 'RESERVED'
                    ), 0)
                ) candidate
                ORDER BY candidate.shop_id
                """, Map.of("platformShopId", platformShopId),
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    /**
     * Applies each paid-sale recovery debt against positive net in the earliest later draft periods.
     * The caller holds the V101 shop finance anchor, serializing this with refunds and payout transitions.
     */
    public List<UUID> allocateOutstandingRecoveryOffsets(UUID shopId) {
        return allocateOutstandingRecoveryOffsets(shopId, null);
    }

    /** Reserves recovery debt against the approved period being reset for a new reapproval. */
    public List<UUID> allocateOutstandingRecoveryOffsetsForPeriod(UUID shopId, UUID periodId) {
        return allocateOutstandingRecoveryOffsets(shopId, periodId);
    }

    private List<UUID> allocateOutstandingRecoveryOffsets(UUID shopId, UUID targetPeriodId) {
        String periodFilter = targetPeriodId == null ? "" : " AND id = :periodId\n";
        MapSqlParameterSource periodParameters = new MapSqlParameterSource().addValue("shopId", shopId);
        if (targetPeriodId != null) periodParameters.addValue("periodId", targetPeriodId);
        List<DraftPeriodBalance> draftPeriods = jdbc.query("""
                SELECT id, period_start_at, credit_amount - debit_amount - recovery_debit_amount AS available
                FROM shop_settlement_periods
                WHERE shop_id = :shopId AND status = 'DRAFT'
                """ + periodFilter + """
                ORDER BY period_start_at, id
                FOR UPDATE
                """, periodParameters, (rs, row) -> new DraftPeriodBalance(
                rs.getObject("id", UUID.class), rs.getTimestamp("period_start_at").toInstant(),
                rs.getLong("available")));
        List<OutstandingRecovery> recoveries = jdbc.query("""
                SELECT recovery.id,
                       recovery.outstanding_amount - coalesce(reserved.amount, 0) AS available_amount,
                       source_period.period_start_at
                FROM shop_payout_recovery_debts recovery
                JOIN shop_payouts payout ON payout.id = recovery.payout_id AND payout.shop_id = recovery.shop_id
                JOIN shop_settlement_periods source_period
                  ON source_period.id = payout.settlement_period_id AND source_period.shop_id = payout.shop_id
                LEFT JOIN LATERAL (
                    SELECT sum(offset_row.amount) AS amount
                    FROM shop_settlement_recovery_offsets offset_row
                    WHERE offset_row.recovery_debt_id = recovery.id
                      AND offset_row.shop_id = recovery.shop_id AND offset_row.status = 'RESERVED'
                ) reserved ON true
                WHERE recovery.shop_id = :shopId AND recovery.outstanding_amount > 0
                  AND recovery.outstanding_amount > coalesce(reserved.amount, 0)
                ORDER BY recovery.created_at, recovery.id
                FOR UPDATE OF recovery
                """, Map.of("shopId", shopId), (rs, row) -> new OutstandingRecovery(
                rs.getObject("id", UUID.class), rs.getLong("available_amount"),
                rs.getTimestamp("period_start_at").toInstant()));

        Set<UUID> changedPeriods = new LinkedHashSet<>();
        for (OutstandingRecovery recovery : recoveries) {
            long outstanding = recovery.outstandingAmount();
            for (DraftPeriodBalance period : draftPeriods) {
                if (outstanding == 0) break;
                if (!period.periodStartAt().isAfter(recovery.sourcePeriodStartAt()) || period.available() <= 0) {
                    continue;
                }
                long offsetAmount = Math.min(outstanding, period.available());
                int inserted = jdbc.update("""
                        INSERT INTO shop_settlement_recovery_offsets(
                            shop_id, recovery_debt_id, period_id, amount, created_at)
                        SELECT :shopId, debt.id, :periodId, :amount, CURRENT_TIMESTAMP
                        FROM shop_payout_recovery_debts debt
                        WHERE debt.id = :debtId AND debt.shop_id = :shopId
                          AND debt.outstanding_amount >= :amount
                        ON CONFLICT (recovery_debt_id, period_id) WHERE status = 'RESERVED' DO NOTHING
                        """, new MapSqlParameterSource().addValue("shopId", shopId)
                        .addValue("periodId", period.id()).addValue("debtId", recovery.id())
                        .addValue("amount", offsetAmount));
                if (inserted == 0) continue;
                outstanding -= offsetAmount;
                period.apply(offsetAmount);
                changedPeriods.add(period.id());
            }
        }
        return new ArrayList<>(changedPeriods);
    }

    public RecoveryOffsetTotals recoveryOffsetTotals(UUID periodId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(amount), 0) AS amount, count(*) AS offset_count
                FROM shop_settlement_recovery_offsets
                WHERE period_id = :periodId AND status IN ('RESERVED', 'APPLIED')
                """, Map.of("periodId", periodId), (rs, row) ->
                new RecoveryOffsetTotals(rs.getLong("amount"), rs.getInt("offset_count")));
    }

    public int releaseRecoveryOffsetsForRecalculation(UUID periodId) {
        return jdbc.update("""
                UPDATE shop_settlement_recovery_offsets
                SET status = 'RELEASED'
                WHERE period_id = :periodId AND status = 'RESERVED'
                """, Map.of("periodId", periodId));
    }

    /** Moves a newly applicable debt off other draft periods before reserving it on the recalculated period. */
    public List<UUID> releaseRecoveryOffsetsForRecoveryRecalculation(UUID targetPeriodId,
                                                                      List<UUID> recoveryDebtIds) {
        if (recoveryDebtIds.isEmpty()) return List.of();
        return jdbc.query("""
                WITH released AS (
                    UPDATE shop_settlement_recovery_offsets offset_row
                    SET status = 'RELEASED'
                    FROM shop_settlement_periods reserved_period
                    WHERE offset_row.period_id = reserved_period.id
                      AND offset_row.shop_id = reserved_period.shop_id
                      AND offset_row.status = 'RESERVED'
                      AND offset_row.period_id <> :targetPeriodId
                      AND offset_row.recovery_debt_id IN (:recoveryDebtIds)
                      AND reserved_period.status = 'DRAFT'
                      AND EXISTS (
                          SELECT 1
                          FROM shop_settlement_periods recalculated_target
                          JOIN shop_settlement_period_recalculations recalculation
                            ON recalculation.id = recalculated_target.last_recalculation_id
                           AND recalculation.shop_id = recalculated_target.shop_id
                           AND recalculation.period_id = recalculated_target.id
                          WHERE recalculated_target.id = :targetPeriodId
                            AND recalculated_target.shop_id = offset_row.shop_id
                            AND recalculated_target.status = 'DRAFT'
                            AND recalculation.reason_code = 'POST_APPROVAL_RECOVERY'
                            AND recalculation.affected_recovery_debt_id = offset_row.recovery_debt_id
                      )
                    RETURNING offset_row.period_id
                )
                SELECT DISTINCT period_id FROM released ORDER BY period_id
                """, new MapSqlParameterSource().addValue("targetPeriodId", targetPeriodId)
                .addValue("recoveryDebtIds", recoveryDebtIds),
                (rs, row) -> rs.getObject("period_id", UUID.class));
    }

    /** Locks debt balances behind this period before the payout boundary and verifies live reservations. */
    public RecoveryOffsetTotals lockPeriodRecoveryOffsets(UUID periodId) {
        jdbc.query("""
                SELECT recovery.id
                FROM shop_payout_recovery_debts recovery
                JOIN shop_settlement_recovery_offsets offset_row
                  ON offset_row.recovery_debt_id = recovery.id AND offset_row.shop_id = recovery.shop_id
                WHERE offset_row.period_id = :periodId AND offset_row.status = 'RESERVED'
                ORDER BY recovery.id
                FOR UPDATE OF recovery, offset_row
                """, Map.of("periodId", periodId), (rs, row) -> rs.getObject(1, UUID.class));
        return recoveryOffsetTotals(periodId);
    }

    /** New refund debits on sales in an approved period are held for that period's audited recalculation. */
    public List<UUID> findUnassignedPostApprovalDebits(ShopSettlementPeriodEntity period) {
        return jdbc.query("""
                SELECT DISTINCT refund.id
                FROM shop_settlement_entries refund
                WHERE refund.shop_id = :shopId AND refund.entry_type IN ('REFUND', 'ADJUSTMENT')
                  AND refund.direction = 'DEBIT' AND refund.created_at >= :approvedAt
                  AND EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries sale_assignment
                      JOIN shop_settlement_entries sale
                        ON sale.id = sale_assignment.settlement_entry_id
                       AND sale.shop_id = sale_assignment.shop_id
                      WHERE sale_assignment.period_id = :periodId
                        AND sale_assignment.shop_id = :shopId
                        AND sale_assignment.entry_type = 'SALE'
                        AND sale.order_id = refund.order_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries assignment
                      WHERE assignment.settlement_entry_id = refund.id
                  )
                ORDER BY refund.id
                """, new MapSqlParameterSource().addValue("shopId", period.getShopId())
                .addValue("periodId", period.getId()).addValue("approvedAt", Timestamp.from(period.getApprovedAt())),
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    public int countPostApprovalDebitsAssignedElsewhere(ShopSettlementPeriodEntity period) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT refund.id)
                FROM shop_settlement_entries refund
                WHERE refund.shop_id = :shopId AND refund.entry_type IN ('REFUND', 'ADJUSTMENT')
                  AND refund.direction = 'DEBIT' AND refund.created_at >= :approvedAt
                  AND EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries sale_assignment
                      JOIN shop_settlement_entries sale
                        ON sale.id = sale_assignment.settlement_entry_id
                       AND sale.shop_id = sale_assignment.shop_id
                      WHERE sale_assignment.period_id = :periodId
                        AND sale_assignment.shop_id = :shopId
                        AND sale_assignment.entry_type = 'SALE'
                        AND sale.order_id = refund.order_id
                  )
                  AND EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries assignment
                      WHERE assignment.settlement_entry_id = refund.id
                        AND assignment.period_id <> :periodId
                  )
                """, new MapSqlParameterSource().addValue("shopId", period.getShopId())
                .addValue("periodId", period.getId()).addValue("approvedAt", Timestamp.from(period.getApprovedAt())),
                Integer.class);
    }

    public int countPostApprovalDebits(ShopSettlementPeriodEntity period) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT refund.id)
                FROM shop_settlement_entries refund
                WHERE refund.shop_id = :shopId AND refund.entry_type IN ('REFUND', 'ADJUSTMENT')
                  AND refund.direction = 'DEBIT' AND refund.created_at >= :approvedAt
                  AND EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries sale_assignment
                      JOIN shop_settlement_entries sale
                        ON sale.id = sale_assignment.settlement_entry_id
                       AND sale.shop_id = sale_assignment.shop_id
                      WHERE sale_assignment.period_id = :periodId
                        AND sale_assignment.shop_id = :shopId
                        AND sale_assignment.entry_type = 'SALE'
                        AND sale.order_id = refund.order_id
                  )
                """, new MapSqlParameterSource().addValue("shopId", period.getShopId())
                .addValue("periodId", period.getId()).addValue("approvedAt", Timestamp.from(period.getApprovedAt())),
                Integer.class);
    }

    /** Locks debts created after approval that have not been reserved on this approved period. */
    public List<UUID> findPostApprovalRecoveryDebtsApplicableToPeriod(ShopSettlementPeriodEntity period) {
        return jdbc.query("""
                SELECT recovery.id
                FROM shop_payout_recovery_debts recovery
                JOIN shop_payouts source_payout
                  ON source_payout.id = recovery.payout_id AND source_payout.shop_id = recovery.shop_id
                JOIN shop_settlement_periods source_period
                  ON source_period.id = source_payout.settlement_period_id
                 AND source_period.shop_id = source_payout.shop_id
                JOIN shop_settlement_periods target_period
                  ON target_period.id = :periodId AND target_period.shop_id = :shopId
                WHERE recovery.shop_id = :shopId
                  AND recovery.outstanding_amount > 0
                  AND recovery.created_at >= :approvedAt
                  AND source_period.period_start_at < target_period.period_start_at
                  AND target_period.credit_amount - target_period.debit_amount
                      - target_period.recovery_debit_amount > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_settlement_recovery_offsets assigned
                      WHERE assigned.recovery_debt_id = recovery.id
                        AND assigned.shop_id = recovery.shop_id
                        AND assigned.period_id = target_period.id
                        AND assigned.status IN ('RESERVED', 'APPLIED')
                  )
                ORDER BY recovery.created_at, recovery.id
                FOR UPDATE OF recovery
                """, new MapSqlParameterSource().addValue("shopId", period.getShopId())
                .addValue("periodId", period.getId()).addValue("approvedAt", Timestamp.from(period.getApprovedAt())),
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    public UUID recordPeriodRecalculation(ShopSettlementPeriodEntity period, UUID actorId, UUID refundEntryId) {
        return jdbc.queryForObject("""
                INSERT INTO shop_settlement_period_recalculations(
                    shop_id, period_id, actor_id, affected_debit_entry_id,
                    prior_approved_by, prior_approved_at, prior_credit_amount, prior_debit_amount,
                    prior_recovery_debit_amount, prior_net_amount, prior_entry_count,
                    reason_code, created_at)
                VALUES (:shopId, :periodId, :actorId, :refundEntryId,
                    :approvedBy, :approvedAt, :creditAmount, :debitAmount,
                    :recoveryDebitAmount, :netAmount, :entryCount,
                    'POST_APPROVAL_DEBIT', CURRENT_TIMESTAMP)
                RETURNING id
                """, new MapSqlParameterSource().addValue("shopId", period.getShopId())
                .addValue("periodId", period.getId()).addValue("actorId", actorId)
                .addValue("refundEntryId", refundEntryId).addValue("approvedBy", period.getApprovedBy())
                .addValue("approvedAt", Timestamp.from(period.getApprovedAt()))
                .addValue("creditAmount", period.getCreditAmount()).addValue("debitAmount", period.getDebitAmount())
                .addValue("recoveryDebitAmount", period.getRecoveryDebitAmount())
                .addValue("netAmount", period.getNetAmount()).addValue("entryCount", period.getEntryCount()),
                UUID.class);
    }

    public UUID recordRecoveryRecalculation(ShopSettlementPeriodEntity period, UUID actorId, UUID recoveryDebtId) {
        return jdbc.queryForObject("""
                INSERT INTO shop_settlement_period_recalculations(
                    shop_id, period_id, actor_id, affected_recovery_debt_id,
                    prior_approved_by, prior_approved_at, prior_credit_amount, prior_debit_amount,
                    prior_recovery_debit_amount, prior_net_amount, prior_entry_count,
                    reason_code, created_at)
                VALUES (:shopId, :periodId, :actorId, :recoveryDebtId,
                    :approvedBy, :approvedAt, :creditAmount, :debitAmount,
                    :recoveryDebitAmount, :netAmount, :entryCount,
                    'POST_APPROVAL_RECOVERY', CURRENT_TIMESTAMP)
                RETURNING id
                """, new MapSqlParameterSource().addValue("shopId", period.getShopId())
                .addValue("periodId", period.getId()).addValue("actorId", actorId)
                .addValue("recoveryDebtId", recoveryDebtId).addValue("approvedBy", period.getApprovedBy())
                .addValue("approvedAt", Timestamp.from(period.getApprovedAt()))
                .addValue("creditAmount", period.getCreditAmount()).addValue("debitAmount", period.getDebitAmount())
                .addValue("recoveryDebitAmount", period.getRecoveryDebitAmount())
                .addValue("netAmount", period.getNetAmount()).addValue("entryCount", period.getEntryCount()),
                UUID.class);
    }

    public int countReservedRecoveryDebts(UUID periodId, List<UUID> recoveryDebtIds) {
        if (recoveryDebtIds.isEmpty()) return 0;
        return jdbc.queryForObject("""
                SELECT count(DISTINCT recovery_debt_id)
                FROM shop_settlement_recovery_offsets
                WHERE period_id = :periodId AND status = 'RESERVED'
                  AND recovery_debt_id IN (:recoveryDebtIds)
                """, new MapSqlParameterSource().addValue("periodId", periodId)
                .addValue("recoveryDebtIds", recoveryDebtIds), Integer.class);
    }

    public int assignPostApprovalDebits(UUID periodId, UUID shopId, List<UUID> debitEntryIds, Instant assignedAt) {
        if (debitEntryIds.isEmpty()) return 0;
        return jdbc.update("""
                INSERT INTO shop_settlement_period_entries(
                    period_id, shop_id, settlement_entry_id, entry_amount, entry_direction,
                    entry_type, source_identity, created_at)
                SELECT :periodId, debit.shop_id, debit.id, debit.amount, debit.direction,
                       debit.entry_type, debit.source_identity, :now
                FROM shop_settlement_entries debit
                WHERE debit.shop_id = :shopId AND debit.id IN (:debitEntryIds)
                  AND debit.entry_type IN ('REFUND', 'ADJUSTMENT')
                  AND debit.direction = 'DEBIT' AND debit.amount > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries assigned
                      WHERE assigned.settlement_entry_id = debit.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_payout_recovery_debts recovery
                      WHERE recovery.refund_entry_id = debit.id AND recovery.shop_id = debit.shop_id
                  )
                ON CONFLICT (settlement_entry_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("periodId", periodId).addValue("shopId", shopId)
                .addValue("debitEntryIds", debitEntryIds).addValue("now", Timestamp.from(assignedAt)));
    }

    private static final class DraftPeriodBalance {
        private final UUID id;
        private final Instant periodStartAt;
        private long available;

        DraftPeriodBalance(UUID id, Instant periodStartAt, long available) {
            this.id = id;
            this.periodStartAt = periodStartAt;
            this.available = available;
        }

        UUID id() { return id; }
        Instant periodStartAt() { return periodStartAt; }
        long available() { return available; }
        void apply(long amount) { available = Math.subtractExact(available, amount); }
    }

    private record OutstandingRecovery(UUID id, long outstandingAmount, Instant sourcePeriodStartAt) { }

    /** Returns the number of currently eligible sources for a shop, before period creation. */
    public int countEligibleUnassignedEntries(UUID shopId, Instant now) {
        String sql = """
                SELECT count(e.id)
                """ + SOURCE_JOINS + """
                WHERE e.shop_id = :shopId
                  AND e.amount > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries assigned
                      WHERE assigned.settlement_entry_id = e.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_payout_recovery_debts recovery
                      WHERE recovery.refund_entry_id = e.id AND recovery.shop_id = e.shop_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries sale_assignment
                      JOIN shop_settlement_periods approved_period
                        ON approved_period.id = sale_assignment.period_id
                       AND approved_period.shop_id = sale_assignment.shop_id
                       AND approved_period.status = 'APPROVED'
                      JOIN shop_settlement_entries sale
                        ON sale.id = sale_assignment.settlement_entry_id
                       AND sale.shop_id = sale_assignment.shop_id
                      WHERE e.entry_type IN ('REFUND', 'ADJUSTMENT') AND e.direction = 'DEBIT'
                        AND sale_assignment.entry_type = 'SALE'
                        AND sale.order_id = e.order_id AND sale.shop_id = e.shop_id
                  )
                  AND (e.direction = 'DEBIT' OR (""" + ELIGIBLE_CREDIT + "))";
        return jdbc.queryForObject(sql, parameters(shopId, now), Integer.class);
    }

    /**
     * Inserts every source that is eligible at this instant. The partial workflow remains safe even
     * if a caller bypasses the finance anchor because the entry identity is database-unique.
     */
    public int assignEligibleUnassignedEntries(UUID periodId, UUID shopId, Instant assignedAt) {
        String sql = """
                INSERT INTO shop_settlement_period_entries(
                    period_id, shop_id, settlement_entry_id, entry_amount, entry_direction,
                    entry_type, source_identity, created_at)
                SELECT :periodId, e.shop_id, e.id, e.amount, e.direction, e.entry_type,
                       e.source_identity, :now
                """ + SOURCE_JOINS + """
                WHERE e.shop_id = :shopId
                  AND e.amount > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries assigned
                      WHERE assigned.settlement_entry_id = e.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_payout_recovery_debts recovery
                      WHERE recovery.refund_entry_id = e.id AND recovery.shop_id = e.shop_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_settlement_period_entries sale_assignment
                      JOIN shop_settlement_periods approved_period
                        ON approved_period.id = sale_assignment.period_id
                       AND approved_period.shop_id = sale_assignment.shop_id
                       AND approved_period.status = 'APPROVED'
                      JOIN shop_settlement_entries sale
                        ON sale.id = sale_assignment.settlement_entry_id
                       AND sale.shop_id = sale_assignment.shop_id
                      WHERE e.entry_type IN ('REFUND', 'ADJUSTMENT') AND e.direction = 'DEBIT'
                        AND sale_assignment.entry_type = 'SALE'
                        AND sale.order_id = e.order_id AND sale.shop_id = e.shop_id
                  )
                  AND (e.direction = 'DEBIT' OR (""" + ELIGIBLE_CREDIT + "))\n"
                + "ON CONFLICT (settlement_entry_id) DO NOTHING";
        return jdbc.update(sql, parameters(shopId, assignedAt).addValue("periodId", periodId));
    }

    /** Rechecks all credit sources at the approval and process boundaries. */
    public int countIneligibleCredits(UUID periodId, Instant now) {
        String sql = """
                SELECT count(e.id)
                """ + SOURCE_JOINS + """
                JOIN shop_settlement_period_entries assignment
                  ON assignment.settlement_entry_id = e.id AND assignment.shop_id = e.shop_id
                WHERE assignment.period_id = :periodId
                  AND e.direction = 'CREDIT'
                  AND NOT (""" + ELIGIBLE_CREDIT + ")";
        return jdbc.queryForObject(sql,
                new MapSqlParameterSource().addValue("periodId", periodId).addValue("now", Timestamp.from(now)),
                Integer.class);
    }

    private static MapSqlParameterSource parameters(UUID shopId, Instant now) {
        return new MapSqlParameterSource().addValue("shopId", shopId).addValue("now", Timestamp.from(now));
    }

}
