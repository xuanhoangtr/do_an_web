package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderItemEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, UUID> {
    List<OrderItemEntity> findByOrderIdOrderByIdAsc(UUID orderId);
    List<OrderItemEntity> findByOrderIdInOrderByOrderIdAscIdAsc(Collection<UUID> orderIds);

    @Query("select distinct item.orderId from OrderItemEntity item where item.productId in :productIds")
    List<UUID> findOrderIdsByProductIdIn(@Param("productIds") Collection<UUID> productIds);
}
