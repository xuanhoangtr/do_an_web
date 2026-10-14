package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderFulfillmentItemEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderFulfillmentItemRepository extends JpaRepository<OrderFulfillmentItemEntity, UUID> {
    List<OrderFulfillmentItemEntity> findByFulfillmentIdOrderByIdAsc(UUID fulfillmentId);
    java.util.Optional<OrderFulfillmentItemEntity> findByFulfillmentIdAndOrderItemId(UUID fulfillmentId, UUID orderItemId);
    List<OrderFulfillmentItemEntity> findByFulfillmentIdIn(Collection<UUID> fulfillmentIds);
    @Query("select link.fulfillmentId, item.id, item.productName, item.quantity, product.sku, "
            + "item.unitPrice, item.lineTotal, product.imageUrl "
            + "from OrderFulfillmentItemEntity link join OrderItemEntity item on item.id = link.orderItemId "
            + "left join ProductEntity product on product.id = item.productId "
            + "where link.fulfillmentId in :fulfillmentIds order by link.fulfillmentId, item.id")
    List<Object[]> findResponseRowsByFulfillmentIdIn(@Param("fulfillmentIds") Collection<UUID> fulfillmentIds);
    long countByFulfillmentId(UUID fulfillmentId);
}
