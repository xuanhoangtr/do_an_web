package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.ProductModerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.time.Instant;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    boolean existsBySku(String sku);
    boolean existsBySkuAndIdNot(String sku, UUID id);
    /** Pageable replaces Prisma skip/take without concatenating SQL. */
    Page<ProductEntity> findByCategoryId(Long categoryId, Pageable pageable);

    Page<ProductEntity> findByActiveTrueOrderByFeaturedDescCreatedAtDesc(Pageable pageable);

    Page<ProductEntity> findByActiveTrueAndCategoryIdOrderByCreatedAtDesc(Long categoryId, Pageable pageable);

    Page<ProductEntity> findByActiveTrueAndIdNotOrderByCreatedAtDesc(UUID id, Pageable pageable);

    Page<ProductEntity> findByActiveTrueAndCategoryIdAndIdNotOrderByCreatedAtDesc(
            Long categoryId, UUID id, Pageable pageable);

    long countByActiveTrueAndStockQuantityLessThanEqual(int stockQuantity);
    Page<ProductEntity> findByActiveTrueOrderByCreatedAtDesc(Pageable pageable);
    Page<ProductEntity> findByActiveTrueOrderByAverageRatingDescReviewCountDesc(Pageable pageable);

    Page<ProductEntity> findByShopIdOrderByUpdatedAtDescIdAsc(UUID shopId, Pageable pageable);

    /** Public shop detail uses the catalog's materialized buyability flag. */
    Page<ProductEntity> findByShopIdAndActiveTrueOrderByCreatedAtDescIdAsc(UUID shopId, Pageable pageable);
    long countByShopIdAndActiveTrue(UUID shopId);

    Optional<ProductEntity> findByIdAndShopId(UUID id, UUID shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductEntity p where p.id = :id and p.shopId = :shopId")
    Optional<ProductEntity> findByIdAndShopIdForUpdate(@Param("id") UUID id, @Param("shopId") UUID shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductEntity p where p.id = :id")
    Optional<ProductEntity> findByIdForModerationUpdate(@Param("id") UUID id);

    @Query("select p from ProductEntity p where p.moderationStatus = :status "
            + "and p.shopId <> :platformShopId "
            + "order by p.updatedAt desc, p.id asc")
    Page<ProductEntity> findVendorProductsForModeration(@Param("status") ProductModerationStatus status,
                                                        @Param("platformShopId") UUID platformShopId,
                                                        Pageable pageable);

    @Query(value = "select p.* from catalog_products p "
            + "where p.active=true and p.id in (select oi.product_id from order_items oi join orders o on o.id=oi.order_id where o.status='PAID') "
            + "order by (select coalesce(sum(oi2.quantity),0) from order_items oi2 join orders o2 on o2.id=oi2.order_id where oi2.product_id=p.id and o2.status='PAID') desc, p.created_at desc limit 20", nativeQuery = true)
    List<ProductEntity> findTopBestSelling();

    long countByActiveTrue();

    long countByCategoryIdAndActiveTrue(Long categoryId);

    @Query("select distinct p.material from ProductEntity p "
            + "where p.active = true and p.material is not null and trim(p.material) <> '' "
            + "order by p.material")
    List<String> findActiveMaterials();

    @Query("select p from ProductEntity p "
            + "where p.active = true "
            + "and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
            + "and (:categoryId is null or p.categoryId = :categoryId) "
            + "and (:material is null or p.material = :material) "
            + "and (:maxPrice is null or p.unitPrice - (p.unitPrice * p.discountPercent / 100) <= :maxPrice)")
    Page<ProductEntity> searchActive(@Param("keyword") String keyword,
                                     @Param("categoryId") Long categoryId,
                                     @Param("material") String material,
                                     @Param("maxPrice") Long maxPrice,
                                     Pageable pageable);

    @Query(value = "select p from ProductEntity p "
            + "where p.active = true "
            + "and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
            + "and (:categoryId is null or p.categoryId = :categoryId) "
            + "and (:material is null or p.material = :material) "
            + "and (:maxPrice is null or p.unitPrice - (p.unitPrice * p.discountPercent / 100) <= :maxPrice) "
            + "order by (p.unitPrice - (p.unitPrice * p.discountPercent / 100)) asc, p.id asc",
            countQuery = "select count(p) from ProductEntity p "
                    + "where p.active = true "
                    + "and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
                    + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
                    + "and (:categoryId is null or p.categoryId = :categoryId) "
                    + "and (:material is null or p.material = :material) "
                    + "and (:maxPrice is null or p.unitPrice - (p.unitPrice * p.discountPercent / 100) <= :maxPrice)")
    Page<ProductEntity> searchActiveByEffectivePriceAsc(@Param("keyword") String keyword,
                                                         @Param("categoryId") Long categoryId,
                                                         @Param("material") String material,
                                                         @Param("maxPrice") Long maxPrice,
                                                         Pageable pageable);

    @Query(value = "select p from ProductEntity p "
            + "where p.active = true "
            + "and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
            + "and (:categoryId is null or p.categoryId = :categoryId) "
            + "and (:material is null or p.material = :material) "
            + "and (:maxPrice is null or p.unitPrice - (p.unitPrice * p.discountPercent / 100) <= :maxPrice) "
            + "order by (p.unitPrice - (p.unitPrice * p.discountPercent / 100)) desc, p.id asc",
            countQuery = "select count(p) from ProductEntity p "
                    + "where p.active = true "
                    + "and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
                    + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
                    + "and (:categoryId is null or p.categoryId = :categoryId) "
                    + "and (:material is null or p.material = :material) "
                    + "and (:maxPrice is null or p.unitPrice - (p.unitPrice * p.discountPercent / 100) <= :maxPrice)")
    Page<ProductEntity> searchActiveByEffectivePriceDesc(@Param("keyword") String keyword,
                                                          @Param("categoryId") Long categoryId,
                                                          @Param("material") String material,
                                                          @Param("maxPrice") Long maxPrice,
                                                          Pageable pageable);

    @Query(value = "select p.* from catalog_products p "
            + "where p.active = true "
            + "and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
            + "and (:categoryId is null or p.category_id = :categoryId) "
            + "and (:material is null or p.material = :material) "
            + "and (:maxPrice is null or p.unit_price - (p.unit_price * p.discount_percent / 100) <= :maxPrice) "
            + "order by (select coalesce(sum(oi.quantity), 0) from order_items oi "
            + "join orders o on o.id = oi.order_id "
            + "where oi.product_id = p.id and o.status = 'PAID') desc, p.created_at desc",
            countQuery = "select count(*) from catalog_products p "
                    + "where p.active = true "
                    + "and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
                    + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
                    + "and (:categoryId is null or p.category_id = :categoryId) "
                    + "and (:material is null or p.material = :material) "
                    + "and (:maxPrice is null or p.unit_price - (p.unit_price * p.discount_percent / 100) <= :maxPrice)",
            nativeQuery = true)
    Page<ProductEntity> searchActiveByPopularity(@Param("keyword") String keyword,
                                                  @Param("categoryId") Long categoryId,
                                                  @Param("material") String material,
                                                  @Param("maxPrice") Long maxPrice,
                                                  Pageable pageable);

    @Query("select p from ProductEntity p "
            + "where (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(p.description, '')) like lower(concat('%', :keyword, '%'))) "
            + "and (:active is null or p.active = :active) "
            + "and (:shopId is null or p.shopId = :shopId) "
            + "and (:outOfStock = false or p.stockQuantity = 0)")
    Page<ProductEntity> searchForAdmin(@Param("keyword") String keyword,
                                       @Param("active") Boolean active,
                                       @Param("outOfStock") boolean outOfStock,
                                       @Param("shopId") UUID shopId,
                                       Pageable pageable);

    Optional<ProductEntity> findByIdAndActiveTrue(UUID id);

    List<ProductEntity> findByIdInAndActiveTrue(List<UUID> ids);

    Optional<ProductEntity> findBySlugIgnoreCaseAndActiveTrue(String slug);

    boolean existsBySlugIgnoreCase(String slug);

    boolean existsBySlugIgnoreCaseAndIdNot(String slug, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductEntity p where p.id = :id and p.active = true")
    Optional<ProductEntity> findByIdForUpdate(UUID id);

    /** Used when cancelling an order: inactive products still need their stock restored. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductEntity p where p.id = :id")
    Optional<ProductEntity> findByIdForUpdateIncludingInactive(@Param("id") UUID id);

    /** Conditional decrement. Returns 1 only when the locked version still has enough stock. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE catalog_products SET stock_quantity = stock_quantity - :quantity, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = :id AND active = true AND seller_visible = true "
            + "AND moderation_status = 'APPROVED' "
            + "AND EXISTS (SELECT 1 FROM shops s WHERE s.id = catalog_products.shop_id AND s.status = 'ACTIVE') "
            + "AND stock_quantity >= :quantity AND version = :version",
            nativeQuery = true)
    int decrementStockIfAvailable(@Param("id") UUID id, @Param("quantity") int quantity,
                                  @Param("version") long version);

    /** Conditional increment paired with the same version token as the locked read. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE catalog_products SET stock_quantity = stock_quantity + :quantity, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = :id AND version = :version",
            nativeQuery = true)
    int incrementStock(@Param("id") UUID id, @Param("quantity") int quantity, @Param("version") long version);

    @Query(value = "select p.id as product_id, p.name as name, p.image_url as image_url, "
            + "p.unit_price as unit_price, "
            + "p.unit_price - (p.unit_price * p.discount_percent / 100) as effective_price, "
            + "coalesce(p.average_rating, 0) as average_rating, "
            + "p.review_count as review_count, p.stock_quantity as stock_quantity, "
            + "sum(oi.quantity) as sold_quantity "
            + "from catalog_products p join order_items oi on oi.product_id = p.id "
            + "join orders o on o.id = oi.order_id "
            + "where p.active = true and o.status = 'PAID' "
            + "and coalesce(o.paid_at, o.created_at) >= :from "
            + "and coalesce(o.paid_at, o.created_at) < :to "
            + "group by p.id, p.name, p.image_url, p.unit_price, p.discount_percent, p.average_rating, p.review_count, p.stock_quantity, p.created_at "
            + "order by sum(oi.quantity) desc, coalesce(p.average_rating, 0) desc, "
            + "p.review_count desc, p.created_at desc",
            nativeQuery = true)
    List<TopSellingRow> findTopSelling(@Param("from") Instant from, @Param("to") Instant to,
                                       org.springframework.data.domain.Pageable pageable);
}
