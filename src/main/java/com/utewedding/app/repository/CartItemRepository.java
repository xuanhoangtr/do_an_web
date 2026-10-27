package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CartItemEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItemEntity, UUID> {
    List<CartItemEntity> findByCartIdOrderByIdAsc(UUID cartId);
    Optional<CartItemEntity> findByCartIdAndProductId(UUID cartId, UUID productId);
    Optional<CartItemEntity> findByIdAndCartId(UUID id, UUID cartId);
    void deleteByCartId(UUID cartId);
    @Modifying(flushAutomatically = true)
    @Query("delete from CartItemEntity i where i.cartId = :cartId and i.id in :rowIds")
    int consumeSelectedRows(@Param("cartId") UUID cartId, @Param("rowIds") List<UUID> rowIds);
}
