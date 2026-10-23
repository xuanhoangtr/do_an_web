package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AdminDashboardAnalyticsRepository extends Repository<OrderEntity, UUID> {

    @Query(value = """
            SELECT periods.period_name AS period,
                   (SELECT COALESCE(SUM(o.total_amount), 0)
                    FROM orders o
                    WHERE o.payment_status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED')
                      AND ((o.paid_at IS NOT NULL AND o.paid_at >= periods.from_at AND o.paid_at < periods.to_at)
                        OR (o.paid_at IS NULL AND o.created_at >= periods.from_at AND o.created_at < periods.to_at)))
                       AS revenue,
                   (SELECT COUNT(*)
                    FROM orders o
                    WHERE o.created_at >= periods.from_at AND o.created_at < periods.to_at) AS orders,
                   (SELECT COUNT(*)
                    FROM users u
                    WHERE u.deleted_at IS NULL
                      AND u.created_at >= periods.from_at AND u.created_at < periods.to_at) AS new_users
            FROM (VALUES
                ('current', CAST(:currentFrom AS timestamptz), CAST(:currentTo AS timestamptz)),
                ('previous', CAST(:previousFrom AS timestamptz), CAST(:previousTo AS timestamptz))
            ) AS periods(period_name, from_at, to_at)
            """, nativeQuery = true)
    List<DashboardMetricRow> aggregatePeriods(@Param("currentFrom") Instant currentFrom,
                                              @Param("currentTo") Instant currentTo,
                                              @Param("previousFrom") Instant previousFrom,
                                              @Param("previousTo") Instant previousTo);

    @Query(value = """
            SELECT d.date AS date,
                   COALESCE(SUM(d.total_amount), 0) AS amount
            FROM (
                SELECT CAST(COALESCE(o.paid_at, o.created_at) AT TIME ZONE CAST(:zoneId AS text) AS date) AS date,
                       o.total_amount
                FROM orders o
                WHERE o.payment_status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED')
                  AND ((o.paid_at IS NOT NULL AND o.paid_at >= :from AND o.paid_at < :to)
                    OR (o.paid_at IS NULL AND o.created_at >= :from AND o.created_at < :to))
            ) d
            GROUP BY d.date
            ORDER BY d.date
            """, nativeQuery = true)
    List<DashboardDailyRevenueRow> findDailyRevenue(@Param("from") Instant from,
                                                    @Param("to") Instant to,
                                                    @Param("zoneId") String zoneId);
}
