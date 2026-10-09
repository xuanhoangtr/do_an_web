package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProductViewHistoryEntity;
import com.utewedding.app.domain.entity.ProductViewHistoryId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductViewHistoryRepository
        extends JpaRepository<ProductViewHistoryEntity, ProductViewHistoryId> {

    @Modifying
    @Query(value = "delete from product_view_history where user_id = :userId and last_viewed_at < :cutoff",
            nativeQuery = true)
    int deleteExpiredForUser(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    @Modifying
    @Query(value = "insert into product_view_history (user_id, product_id, last_viewed_at) "
            + "values (:userId, :productId, :viewedAt) "
            + "on conflict (user_id, product_id) do update "
            + "set last_viewed_at = greatest(product_view_history.last_viewed_at, excluded.last_viewed_at)",
            nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("productId") UUID productId,
               @Param("viewedAt") Instant viewedAt);

    @Modifying
    @Query(value = "delete from product_view_history where user_id = :userId and product_id in ("
            + "select product_id from product_view_history where user_id = :userId "
            + "order by last_viewed_at desc, product_id desc offset 50)", nativeQuery = true)
    int pruneToFifty(@Param("userId") UUID userId);

    @Query(value = "select h.product_id from product_view_history h "
            + "join catalog_products p on p.id = h.product_id and p.active = true "
            + "where h.user_id = :userId and h.last_viewed_at >= :cutoff "
            + "order by h.last_viewed_at desc, h.product_id desc",
            countQuery = "select count(*) from product_view_history h "
                    + "join catalog_products p on p.id = h.product_id and p.active = true "
                    + "where h.user_id = :userId and h.last_viewed_at >= :cutoff",
            nativeQuery = true)
    Page<UUID> findRecentActiveProductIds(@Param("userId") UUID userId,
                                          @Param("cutoff") Instant cutoff,
                                          Pageable pageable);

    @Query(value = "select h.product_id from product_view_history h "
            + "join catalog_products p on p.id = h.product_id and p.active = true "
            + "where h.user_id = :userId and h.product_id <> :excludedProductId "
            + "and h.last_viewed_at >= :cutoff "
            + "order by h.last_viewed_at desc, h.product_id desc",
            nativeQuery = true)
    List<UUID> findRecentActiveProductIdsExcluding(@Param("userId") UUID userId,
                                                   @Param("excludedProductId") UUID excludedProductId,
                                                   @Param("cutoff") Instant cutoff,
                                                   Pageable pageable);

    @Query("select h from ProductViewHistoryEntity h where h.userId = :userId "
            + "and h.lastViewedAt >= :cutoff order by h.lastViewedAt desc, h.productId desc")
    List<ProductViewHistoryEntity> findRecentForExport(@Param("userId") UUID userId,
                                                        @Param("cutoff") Instant cutoff);

    @Modifying
    @Query(value = "delete from product_view_history where user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "delete from product_view_history where last_viewed_at < :cutoff", nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
