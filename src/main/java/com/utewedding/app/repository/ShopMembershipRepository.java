package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopMembershipRole;
import com.utewedding.app.domain.ShopMembershipStatus;
import com.utewedding.app.domain.entity.ShopMembershipEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopMembershipRepository extends JpaRepository<ShopMembershipEntity, UUID> {
    Optional<ShopMembershipEntity> findByShopIdAndUserId(UUID shopId, UUID userId);

    List<ShopMembershipEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            UUID userId, ShopMembershipStatus status);

    List<ShopMembershipEntity> findByUserIdAndRoleAndStatus(
            UUID userId, ShopMembershipRole role, ShopMembershipStatus status);

    Optional<ShopMembershipEntity> findByShopIdAndRoleAndStatus(
            UUID shopId, ShopMembershipRole role, ShopMembershipStatus status);

    boolean existsByShopIdAndUserIdAndStatus(UUID shopId, UUID userId, ShopMembershipStatus status);

    /**
     * Locks a membership after its parent shop row has been locked. Revocation
     * flows must use the same shop-then-membership order as vendor mutations.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ShopMembershipEntity m where m.shopId = :shopId and m.userId = :userId")
    Optional<ShopMembershipEntity> findByShopIdAndUserIdForUpdate(
            @Param("shopId") UUID shopId, @Param("userId") UUID userId);

    /** Locks the single active owner after the parent shop lock has been acquired. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ShopMembershipEntity m where m.shopId = :shopId "
            + "and m.role = :role and m.status = :status")
    Optional<ShopMembershipEntity> findByShopIdAndRoleAndStatusForUpdate(
            @Param("shopId") UUID shopId,
            @Param("role") ShopMembershipRole role,
            @Param("status") ShopMembershipStatus status);
}
