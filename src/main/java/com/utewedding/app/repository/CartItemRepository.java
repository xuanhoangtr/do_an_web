package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CartItemEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItemEntity, UUID> {
    List<CartItemEntity> findByCartIdOrderByIdAsc(UUID cartId);
    Optional<CartItemEntity> findByCartIdAndProductId(UUID cartId, UUID productId);
    void deleteByCartId(UUID cartId);
}
