package com.utewedding.app.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/** Validates cross-domain support references against both the authenticated customer and shop. */
@Repository
public class ShopSupportReferenceRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public boolean orderBelongsTo(UUID orderId, UUID customerId, UUID shopId) {
        return exists("select exists(select 1 from orders o where o.id = :ref and o.user_id = :customer "
                + "and o.shop_id = :shop)", orderId, customerId, shopId);
    }

    public boolean paymentBelongsTo(UUID paymentId, UUID customerId, UUID shopId) {
        return exists("select exists(select 1 from payments p join orders o on o.id = p.order_id "
                + "where p.id = :ref and o.user_id = :customer and o.shop_id = :shop)",
                paymentId, customerId, shopId);
    }

    public boolean returnCaseBelongsTo(UUID returnCaseId, UUID customerId, UUID shopId) {
        return exists("select exists(select 1 from return_cases r join orders o on o.id = r.order_id "
                + "where r.id = :ref and r.customer_id = :customer and r.shop_id = :shop "
                + "and o.user_id = :customer and o.shop_id = :shop)", returnCaseId, customerId, shopId);
    }

    public boolean returnDisputeBelongsTo(UUID disputeId, UUID customerId, UUID shopId) {
        return exists("select exists(select 1 from return_disputes d "
                + "join return_cases r on r.id = d.return_case_id "
                + "join orders o on o.id = r.order_id "
                + "where d.id = :ref and r.customer_id = :customer and r.shop_id = :shop "
                + "and o.user_id = :customer and o.shop_id = :shop)", disputeId, customerId, shopId);
    }

    public boolean fulfillmentBelongsTo(UUID fulfillmentId, UUID customerId, UUID shopId) {
        return exists("select exists(select 1 from order_fulfillments f "
                + "join orders o on o.id = f.order_id "
                + "where f.id = :ref and f.shop_id = :shop and o.shop_id = :shop and o.user_id = :customer)",
                fulfillmentId, customerId, shopId);
    }

    private boolean exists(String sql, UUID referenceId, UUID customerId, UUID shopId) {
        Object value = entityManager.createNativeQuery(sql)
                .setParameter("ref", referenceId)
                .setParameter("customer", customerId)
                .setParameter("shop", shopId)
                .getSingleResult();
        return Boolean.TRUE.equals(value);
    }
}
