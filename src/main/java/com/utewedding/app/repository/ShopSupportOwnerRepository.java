package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopMembershipEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Resolves active shop owners without trusting a caller-supplied owner id. */
public interface ShopSupportOwnerRepository extends JpaRepository<ShopMembershipEntity, UUID> {
    @Query("select m.userId from ShopMembershipEntity m join UserEntity u on u.id = m.userId "
            + "where m.shopId = :shopId "
            + "and m.role = com.utewedding.app.domain.ShopMembershipRole.OWNER "
            + "and m.status = com.utewedding.app.domain.ShopMembershipStatus.ACTIVE "
            + "and m.revokedAt is null "
            + "and u.status = com.utewedding.app.domain.UserStatus.ACTIVE "
            + "and u.deletedAt is null "
            + "and u.emailVerified = true")
    List<UUID> findActiveOwnerIds(@Param("shopId") UUID shopId);
}
