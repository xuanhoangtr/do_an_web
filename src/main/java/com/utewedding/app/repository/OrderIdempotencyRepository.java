package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderIdempotencyEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderIdempotencyRepository extends JpaRepository<OrderIdempotencyEntity, String> {

    /**
     * PostgreSQL holds the primary-key row lock until the checkout transaction
     * commits, so a concurrent retry waits and then observes the stored hash.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO order_idempotency_keys "
            + "(idempotency_key, user_id, request_hash, created_at) "
            + "VALUES (:key, :userId, :requestHash, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (idempotency_key) DO NOTHING", nativeQuery = true)
    int claimIfAbsent(@Param("key") String key, @Param("userId") UUID userId,
                      @Param("requestHash") String requestHash);
}
