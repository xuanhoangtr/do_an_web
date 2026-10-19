package com.utewedding.app.repository;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.service.web.VendorDashboardMetrics;
import com.utewedding.app.service.web.VendorDashboardOrder;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Native read projections that always require an already-authorized shop id. */
@Repository
public class VendorDashboardQueryRepository {
    private static final String METRICS_SQL = """
            SELECT
              %s AS orders_in_range,
              %s AS open_orders,
              (SELECT count(*) FROM catalog_products p
               WHERE p.shop_id = :shopId AND p.active = true AND p.seller_visible = true
                 AND p.moderation_status = 'APPROVED') AS active_products,
              (SELECT coalesce(sum(p.stock_quantity), 0) FROM catalog_products p
               WHERE p.shop_id = :shopId AND p.active = true AND p.seller_visible = true
                 AND p.moderation_status = 'APPROVED') AS stock_units,
              (SELECT count(*) FROM catalog_products p
               WHERE p.shop_id = :shopId AND p.active = true AND p.seller_visible = true
                 AND p.moderation_status = 'APPROVED' AND p.stock_quantity BETWEEN 1 AND 5) AS low_stock_products,
              (SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
               FROM shop_settlement_entries e
               WHERE e.shop_id = :shopId AND e.created_at >= :fromAt AND e.created_at < :toAt) AS ledger_net
            """;

    private static final String RECENT_ORDERS_SQL = """
            SELECT o.id, o.order_number, o.status, o.created_at
            FROM orders o
            WHERE o.shop_id = :shopId
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT 5
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public VendorDashboardQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VendorDashboardMetrics metrics(UUID shopId, Instant fromAt, Instant toAt, boolean ordersEnabled) {
        requireShopAndRange(shopId, fromAt, toAt);
        var parameters = parameters(shopId, fromAt, toAt);
        String orderCountSql = ordersEnabled
                ? "(SELECT count(*) FROM orders o WHERE o.shop_id = :shopId "
                  + "AND o.created_at >= :fromAt AND o.created_at < :toAt)"
                : "0";
        String openOrdersSql = ordersEnabled
                ? "(SELECT count(*) FROM orders o WHERE o.shop_id = :shopId "
                  + "AND o.status NOT IN ('CANCELLED', 'EXPIRED', 'REFUNDED'))"
                : "0";
        return jdbc.queryForObject(METRICS_SQL.formatted(orderCountSql, openOrdersSql), parameters, (rs, row) -> {
            long orderCount = rs.getLong("orders_in_range");
            long openOrders = rs.getLong("open_orders");
            BigDecimal netValue = rs.getBigDecimal("ledger_net");
            long ledgerNet;
            try {
                ledgerNet = netValue == null ? 0 : netValue.longValueExact();
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("Shop ledger summary exceeds the supported amount range.", overflow);
            }
            return new VendorDashboardMetrics(orderCount, openOrders,
                    rs.getLong("active_products"), rs.getLong("stock_units"),
                    rs.getLong("low_stock_products"), ledgerNet);
        });
    }

    public List<VendorDashboardOrder> recentOrders(UUID shopId) {
        if (shopId == null) throw new IllegalArgumentException("Shop scope is required.");
        return jdbc.query(RECENT_ORDERS_SQL, new MapSqlParameterSource("shopId", shopId),
                (rs, row) -> new VendorDashboardOrder(rs.getObject("id", UUID.class),
                        rs.getString("order_number"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()));
    }

    private static MapSqlParameterSource parameters(UUID shopId, Instant fromAt, Instant toAt) {
        return new MapSqlParameterSource()
                .addValue("shopId", shopId)
                .addValue("fromAt", Timestamp.from(fromAt))
                .addValue("toAt", Timestamp.from(toAt));
    }

    private static void requireShopAndRange(UUID shopId, Instant fromAt, Instant toAt) {
        if (shopId == null || fromAt == null || toAt == null || !fromAt.isBefore(toAt)) {
            throw new IllegalArgumentException("A valid shop and date range are required.");
        }
    }
}
