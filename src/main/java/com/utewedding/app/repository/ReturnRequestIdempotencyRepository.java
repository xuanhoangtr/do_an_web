package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ReturnRequestIdempotencyEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnRequestIdempotencyRepository extends JpaRepository<ReturnRequestIdempotencyEntity, ReturnRequestIdempotencyEntity.Key> {
    Optional<ReturnRequestIdempotencyEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);
}
