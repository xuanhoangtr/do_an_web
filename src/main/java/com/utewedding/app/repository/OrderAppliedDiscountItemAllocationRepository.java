package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderAppliedDiscountItemAllocationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderAppliedDiscountItemAllocationRepository
        extends JpaRepository<OrderAppliedDiscountItemAllocationEntity, UUID> {
    List<OrderAppliedDiscountItemAllocationEntity> findAllByOrderIdOrderByOrderItemIdAsc(UUID orderId);
}
