package com.utewedding.app.repository;

import com.utewedding.app.service.web.AdminVendorFinanceProjection;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Aggregate finance projection across Vendor shops only; does not expose destination or proof data. */
@Repository
public class AdminVendorFinanceQueryRepository {
    private static final String SUMMARY_SQL = """
            WITH vendor_shops AS (
              SELECT id FROM shops WHERE kind='VENDOR'
            ),
            holds_by_order AS (
              SELECT h.shop_id, h.order_id, sum(h.amount) AS held_amount
              FROM shop_finance_holds h JOIN vendor_shops vs ON vs.id=h.shop_id
              WHERE h.status='ACTIVE'
              GROUP BY h.shop_id, h.order_id
            ),
            period_order_net AS (
              SELECT pe.period_id, pe.shop_id, e.order_id,
                     sum(CASE WHEN pe.entry_direction='CREDIT' THEN pe.entry_amount ELSE -pe.entry_amount END) AS net
              FROM shop_settlement_period_entries pe
              JOIN shop_settlement_entries e ON e.id=pe.settlement_entry_id AND e.shop_id=pe.shop_id
              JOIN vendor_shops vs ON vs.id=pe.shop_id
              GROUP BY pe.period_id, pe.shop_id, e.order_id
            ),
            eligible_period_order_net AS (
              SELECT po.period_id, po.shop_id, po.order_id, po.net, p.period_start_at,
                     coalesce(h.held_amount,0) AS order_hold,
                     coalesce(sum(greatest(po.net,0)) OVER (
                       PARTITION BY po.shop_id, po.order_id
                       ORDER BY p.period_start_at, po.period_id
                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                     ),0) AS positive_net_before
              FROM period_order_net po
              JOIN shop_settlement_periods p ON p.id=po.period_id AND p.shop_id=po.shop_id
              LEFT JOIN holds_by_order h ON h.shop_id=po.shop_id AND h.order_id=po.order_id
              WHERE p.status IN ('DRAFT','APPROVED')
            ),
            period_order_hold AS (
              SELECT period_id, shop_id, order_id,
                     least(greatest(net,0), greatest(order_hold-positive_net_before,0)) AS held_amount
              FROM eligible_period_order_net
            ),
            period_hold AS (
              SELECT period_id, shop_id, sum(held_amount) AS held_amount
              FROM period_order_hold
              GROUP BY period_id, shop_id
            ),
            assigned_hold_by_order AS (
              SELECT shop_id, order_id, sum(held_amount) AS held_amount
              FROM period_order_hold
              GROUP BY shop_id, order_id
            ),
            assigned_periods AS (
              SELECT p.id, p.shop_id, p.status, p.net_amount,
                     CASE WHEN p.status IN ('DRAFT','APPROVED')
                          THEN least(greatest(p.net_amount,0),coalesce(ph.held_amount,0)) ELSE 0 END AS held_amount,
                     coalesce(payout.status,'') AS payout_status,
                     EXISTS (SELECT 1 FROM shop_payouts active_payout
                             WHERE active_payout.settlement_period_id=p.id AND active_payout.shop_id=p.shop_id
                               AND active_payout.status <> 'CANCELLED') AS payout_reserved
              FROM shop_settlement_periods p
              JOIN vendor_shops vs ON vs.id=p.shop_id
              LEFT JOIN period_hold ph ON ph.period_id=p.id AND ph.shop_id=p.shop_id
              LEFT JOIN shop_payouts payout ON payout.settlement_period_id=p.id AND payout.shop_id=p.shop_id
                AND payout.status <> 'CANCELLED'
            ),
            unassigned_order AS (
              SELECT e.shop_id, e.order_id,
                     sum(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END) AS net
              FROM shop_settlement_entries e JOIN vendor_shops vs ON vs.id=e.shop_id
              WHERE NOT EXISTS (SELECT 1 FROM shop_settlement_period_entries pe
                                WHERE pe.settlement_entry_id=e.id AND pe.shop_id=e.shop_id)
                AND NOT EXISTS (SELECT 1 FROM shop_payout_recovery_debts rd
                                WHERE rd.refund_entry_id=e.id AND rd.shop_id=e.shop_id)
              GROUP BY e.shop_id, e.order_id
            ),
            unassigned_bucket AS (
              SELECT u.shop_id, u.order_id, u.net,
                     least(greatest(u.net,0),greatest(coalesce(h.held_amount,0)
                       - coalesce(ah.held_amount,0),0)) AS held_amount
              FROM unassigned_order u
              LEFT JOIN holds_by_order h ON h.shop_id=u.shop_id AND h.order_id=u.order_id
              LEFT JOIN assigned_hold_by_order ah ON ah.shop_id=u.shop_id AND ah.order_id=u.order_id
            ),
            ledger_range AS (
              SELECT coalesce(sum(e.amount) FILTER (WHERE e.entry_type='SALE' AND e.direction='CREDIT'),0) sales,
                     coalesce(sum(e.amount) FILTER (WHERE e.entry_type='REFUND' AND e.direction='DEBIT'),0) refunds
              FROM shop_settlement_entries e JOIN vendor_shops vs ON vs.id=e.shop_id
              WHERE e.created_at>=:fromAt AND e.created_at<:toAt
            )
            SELECT
              (SELECT count(*) FROM vendor_shops) AS shop_count,
              coalesce((SELECT sum(net_amount) FROM assigned_periods
                        WHERE status='APPROVED' AND NOT payout_reserved AND held_amount=0),0) AS available_amount,
              coalesce((SELECT sum(net-held_amount) FROM unassigned_bucket),0)
                + coalesce((SELECT sum(net_amount-held_amount) FROM assigned_periods
                            WHERE status='DRAFT' OR (status='APPROVED' AND payout_status <> 'PAID'
                              AND (payout_status IN ('REQUESTED','APPROVED') OR held_amount>0))
                               OR (status='PROCESSING' AND payout_status='PROCESSING')),0)
                AS pending_amount,
              coalesce((SELECT sum(held_amount) FROM unassigned_bucket),0)
                + coalesce((SELECT sum(held_amount) FROM assigned_periods
                            WHERE status IN ('DRAFT','APPROVED') AND payout_status NOT IN ('PROCESSING','PAID')),0)
                AS held_amount,
              coalesce((SELECT sum(p.amount) FROM shop_payouts p JOIN vendor_shops vs ON vs.id=p.shop_id
                        WHERE p.status='PAID' AND p.provider_reference IS NOT NULL AND p.proof_media_id IS NOT NULL),0)
                AS paid_amount,
              coalesce((SELECT sum(d.outstanding_amount) FROM shop_payout_recovery_debts d
                        JOIN vendor_shops vs ON vs.id=d.shop_id),0) AS recovery_amount,
              ledger_range.sales, ledger_range.refunds,
              coalesce((SELECT sum(s.commission_amount)
                        FROM shop_order_financial_snapshots s
                        JOIN vendor_shops vs ON vs.id=s.shop_id
                        JOIN shop_settlement_entries sale ON sale.order_id=s.order_id
                          AND sale.shop_id=s.shop_id AND sale.entry_type='SALE' AND sale.direction='CREDIT'
                        WHERE sale.created_at>=:fromAt AND sale.created_at<:toAt),0) AS commission_amount,
              coalesce((SELECT sum(p.amount) FROM shop_payouts p JOIN vendor_shops vs ON vs.id=p.shop_id
                        WHERE p.status='PAID' AND p.provider_reference IS NOT NULL AND p.proof_media_id IS NOT NULL
                          AND p.paid_at>=:fromAt AND p.paid_at<:toAt),0) AS paid_in_range
            FROM ledger_range
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminVendorFinanceQueryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AdminVendorFinanceProjection summary(Instant fromAt, Instant toAt) {
        if (fromAt == null || toAt == null || !fromAt.isBefore(toAt)) {
            throw new IllegalArgumentException("A valid Vendor finance date range is required.");
        }
        return jdbc.queryForObject(SUMMARY_SQL,
                new MapSqlParameterSource().addValue("fromAt", Timestamp.from(fromAt))
                        .addValue("toAt", Timestamp.from(toAt)), (rs, row) ->
                        new AdminVendorFinanceProjection(rs.getLong("shop_count"),
                                amount(rs.getBigDecimal("available_amount")),
                                amount(rs.getBigDecimal("pending_amount")),
                                amount(rs.getBigDecimal("held_amount")),
                                amount(rs.getBigDecimal("paid_amount")),
                                amount(rs.getBigDecimal("recovery_amount")),
                                amount(rs.getBigDecimal("sales")), amount(rs.getBigDecimal("refunds")),
                                amount(rs.getBigDecimal("commission_amount")),
                                amount(rs.getBigDecimal("paid_in_range"))));
    }

    private static long amount(BigDecimal value) {
        try {
            return value == null ? 0L : value.longValueExact();
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Vendor finance projection exceeds the supported amount range.", overflow);
        }
    }
}
