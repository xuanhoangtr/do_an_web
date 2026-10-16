package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderCarrierCostEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderCarrierCostRepository extends JpaRepository<OrderCarrierCostEntity, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO order_carrier_costs (order_id, shop_id, provider_code, fee, currency, source, checked_at)
            SELECT o.id, o.shop_id, 'GHN', :fee, 'VND', :source, CURRENT_TIMESTAMP
            FROM orders o
            WHERE o.id = :orderId
            ON CONFLICT (order_id) DO UPDATE SET
                fee = EXCLUDED.fee,
                source = EXCLUDED.source,
                checked_at = EXCLUDED.checked_at
            WHERE order_carrier_costs.shop_id = EXCLUDED.shop_id
              AND (order_carrier_costs.source <> 'CREATED' OR EXCLUDED.source = 'CREATED')
            """, nativeQuery = true)
    int upsertGhnCost(@Param("orderId") UUID orderId, @Param("fee") long fee,
                      @Param("source") String source);
}
