package com.utewedding.app.repository;

import com.utewedding.app.domain.ProductReviewStatus;
import com.utewedding.app.domain.entity.ProductReviewEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface ProductReviewRepository extends JpaRepository<ProductReviewEntity, UUID> {
    Page<ProductReviewEntity> findByProductIdAndStatusOrderByCreatedAtDesc(UUID productId,
            ProductReviewStatus status, Pageable pageable);
    Page<ProductReviewEntity> findByStatusOrderByCreatedAtAsc(ProductReviewStatus status, Pageable pageable);
    Optional<ProductReviewEntity> findByProductIdAndUserId(UUID productId, UUID userId);
    boolean existsByProductIdAndUserId(UUID productId, UUID userId);
    long countByProductIdAndStatus(UUID productId, ProductReviewStatus status);
    @Query("select coalesce(avg(r.rating), 0) from ProductReviewEntity r where r.productId = :productId and r.status = :status")
    BigDecimal averageRating(@Param("productId") UUID productId, @Param("status") ProductReviewStatus status);
    @Query("select oi.orderId from OrderItemEntity oi join OrderEntity o on o.id = oi.orderId "
            + "where oi.productId = :productId and o.userId = :userId "
            + "and o.status = com.utewedding.app.domain.OrderStatus.PAID "
            + "order by o.paidAt desc, o.createdAt desc")
    List<UUID> findPaidOrderIds(@Param("productId") UUID productId, @Param("userId") UUID userId);

    Page<ProductReviewEntity> findAllByOrderByCreatedAtAsc(Pageable pageable);
    Page<ProductReviewEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    List<ProductReviewEntity> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByStatus(ProductReviewStatus status);

    @Query("""
            select r from ProductReviewEntity r, ProductEntity p
            where r.productId = p.id
              and (:status is null or r.status = :status)
              and (:rating is null or r.rating = :rating)
              and (:productId is null or r.productId = :productId)
              and (:userId is null or r.userId = :userId)
              and (cast(:from as timestamp) is null or r.createdAt >= :from)
              and (cast(:to as timestamp) is null or r.createdAt < :to)
              and (cast(:query as string) is null or lower(r.content) like lower(concat('%', cast(:query as string), '%'))
                   or lower(p.name) like lower(concat('%', cast(:query as string), '%')))
            """)
    Page<ProductReviewEntity> searchForModeration(@Param("query") String query,
                                                   @Param("status") ProductReviewStatus status,
                                                   @Param("rating") Integer rating,
                                                   @Param("productId") UUID productId,
                                                   @Param("userId") UUID userId,
                                                   @Param("from") java.time.Instant from,
                                                   @Param("to") java.time.Instant to,
                                                   Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductReviewEntity r where r.id = :id")
    Optional<ProductReviewEntity> findByIdForUpdate(@Param("id") UUID id);
}
