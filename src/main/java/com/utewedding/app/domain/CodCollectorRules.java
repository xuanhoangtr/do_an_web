package com.utewedding.app.domain;

import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import java.util.Collection;
import java.util.List;

/** Stable COD collector selection for orders with multiple fulfillment parcels. */
public final class CodCollectorRules {
    private CodCollectorRules() {
    }

    public static boolean canReassign(Collection<FulfillmentStatus> statuses) {
        return statuses == null || statuses.stream().noneMatch(status ->
                status != null && status.blocksCodReassignment());
    }

    /**
     * Marks the first fulfillment as the only COD collector when none is set.
     *
     * @return {@code true} when any row changed
     */
    public static boolean applyIfAbsent(List<OrderFulfillmentEntity> stableOrder, long totalAmount) {
        if (stableOrder == null || stableOrder.isEmpty()) return false;
        if (stableOrder.stream().anyMatch(OrderFulfillmentEntity::isCollectsCod)) return false;
        boolean changed = false;
        for (int index = 0; index < stableOrder.size(); index++) {
            OrderFulfillmentEntity row = stableOrder.get(index);
            boolean collects = index == 0;
            long amount = collects ? Math.max(0, totalAmount) : 0;
            if (row.isCollectsCod() != collects || row.getCodAmount() != amount) {
                row.setCollectsCod(collects);
                row.setCodAmount(amount);
                changed = true;
            }
        }
        return changed;
    }

    public static void reassign(List<OrderFulfillmentEntity> rows, java.util.UUID collectorId, long totalAmount) {
        boolean found = false;
        for (OrderFulfillmentEntity row : rows) {
            boolean collects = row.getId() != null && row.getId().equals(collectorId);
            if (collects) found = true;
            row.setCollectsCod(collects);
            row.setCodAmount(collects ? Math.max(0, totalAmount) : 0);
        }
        if (!found) {
            throw new IllegalArgumentException("COD collector is not part of the order");
        }
    }
}
