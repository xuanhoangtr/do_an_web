package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.entity.ShopEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopRepository extends JpaRepository<ShopEntity, UUID> {
    Optional<ShopEntity> findByCodeIgnoreCase(String code);
    Optional<ShopEntity> findBySlugIgnoreCase(String slug);
    Optional<ShopEntity> findBySlugIgnoreCaseAndKindAndStatus(String slug, ShopKind kind, ShopStatus status);
    List<ShopEntity> findByKind(ShopKind kind);

    /** Public directory projection; the joined count is computed for the page in one query. */
    @Query(value = "select s.id as id, s.slug as slug, s.displayName as displayName, "
            + "s.description as description, s.logoMediaId as logoMediaId, "
            + "s.bannerMediaId as bannerMediaId, s.createdAt as joinedAt, count(p.id) as activeProductCount "
            + "from ShopEntity s left join ProductEntity p on p.shopId = s.id and p.active = true "
            + "where s.kind = com.utewedding.app.domain.ShopKind.VENDOR "
            + "and s.status = com.utewedding.app.domain.ShopStatus.ACTIVE "
            + "and (:keyword = '' or lower(s.displayName) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(s.description, '')) like lower(concat('%', :keyword, '%')) "
            + "or lower(s.slug) like lower(concat('%', :keyword, '%'))) "
            + "group by s.id, s.slug, s.displayName, s.description, s.logoMediaId, s.bannerMediaId, s.createdAt "
            + "order by lower(s.displayName), s.id",
            countQuery = "select count(s) from ShopEntity s "
                    + "where s.kind = com.utewedding.app.domain.ShopKind.VENDOR "
                    + "and s.status = com.utewedding.app.domain.ShopStatus.ACTIVE "
                    + "and (:keyword = '' or lower(s.displayName) like lower(concat('%', :keyword, '%')) "
                    + "or lower(coalesce(s.description, '')) like lower(concat('%', :keyword, '%')) "
                    + "or lower(s.slug) like lower(concat('%', :keyword, '%')))" )
    Page<PublicShopProjection> searchPublicShops(@Param("keyword") String keyword, Pageable pageable);

    interface PublicShopProjection {
        UUID getId();
        String getSlug();
        String getDisplayName();
        String getDescription();
        UUID getLogoMediaId();
        UUID getBannerMediaId();
        java.time.Instant getJoinedAt();
        long getActiveProductCount();
    }

    /**
     * Locks the shop row. Membership mutation paths must acquire this lock before
     * locking or changing membership rows, so shop suspension serializes with
     * authorized vendor writes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShopEntity s where s.id = :shopId")
    Optional<ShopEntity> findByIdForUpdate(@Param("shopId") UUID shopId);
}
