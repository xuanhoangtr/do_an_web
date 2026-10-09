package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProductFavoriteEntity;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductFavoriteRepository extends JpaRepository<ProductFavoriteEntity, UUID> {
    Optional<ProductFavoriteEntity> findByUserIdAndProductId(UUID userId, UUID productId);
    boolean existsByUserIdAndProductId(UUID userId, UUID productId);
    @Modifying
    @Query(value = "insert into product_favorites (user_id, product_id) values (:userId, :productId) "
            + "on conflict (user_id, product_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("productId") UUID productId);
    long deleteByUserIdAndProductId(UUID userId, UUID productId);
    long deleteByUserId(UUID userId);
    long countByProductId(UUID productId);
    org.springframework.data.domain.Page<ProductFavoriteEntity> findByUserIdOrderByCreatedAtDesc(
            UUID userId, org.springframework.data.domain.Pageable pageable);
    @Query(value = "select f.product_id from product_favorites f join catalog_products p on p.id=f.product_id where p.active=true group by f.product_id order by count(*) desc, f.product_id limit 20", nativeQuery = true)
    List<UUID> findTopProductIds();
}
