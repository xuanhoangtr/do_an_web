package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopMembershipRole;
import com.utewedding.app.domain.ShopMembershipStatus;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.ShopMembershipEntity;
import com.utewedding.app.repository.ShopMembershipRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.security.AppRole;
import com.utewedding.app.security.UserPrincipal;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-layer authorization for operations owned by a vendor shop.
 *
 * <p>The shop id is only a requested resource identifier. The actor identity is
 * taken from the authenticated {@link UserPrincipal}, then matched against an
 * active OWNER membership and a VENDOR shop. Suspended shops retain read-only
 * access so owners can inspect existing history and obligations.</p>
 */
@Service
public class ShopAccessPolicy {
    private static final String DENIED = "Bạn không có quyền truy cập cửa hàng này.";

    private final ShopRepository shops;
    private final ShopMembershipRepository memberships;

    public ShopAccessPolicy(ShopRepository shops, ShopMembershipRepository memberships) {
        this.shops = shops;
        this.memberships = memberships;
    }

    /** Checks owner read access; suspended shops remain readable for history and open obligations. */
    @Transactional(readOnly = true)
    public ShopAccess requireRead(UserPrincipal actor, UUID shopId) {
        UUID userId = authenticatedCustomerId(actor, shopId);
        ShopEntity shop = shops.findById(shopId).orElseThrow(ShopAccessPolicy::denied);
        requireReadableVendorShop(shop);
        ShopMembershipEntity membership = memberships.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(ShopAccessPolicy::denied);
        requireActiveOwnerMembership(membership, shopId, userId);
        return new ShopAccess(shop, membership);
    }

    /**
     * Checks and locks write access. Call only from a write transaction and keep
     * that transaction open through the protected mutation. The shop row is
     * always locked before the membership row; suspend/revoke workflows must use
     * the same order. This serializes a write with shop suspension or membership
     * revocation instead of authorizing from an unlocked snapshot.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ShopAccess requireMutation(UserPrincipal actor, UUID shopId) {
        UUID userId = authenticatedCustomerId(actor, shopId);
        ShopEntity shop = shops.findByIdForUpdate(shopId).orElseThrow(ShopAccessPolicy::denied);
        requireActiveVendorShop(shop);
        ShopMembershipEntity membership = memberships.findByShopIdAndUserIdForUpdate(shopId, userId)
                .orElseThrow(ShopAccessPolicy::denied);
        requireActiveOwnerMembership(membership, shopId, userId);
        return new ShopAccess(shop, membership);
    }

    private UUID authenticatedCustomerId(UserPrincipal actor, UUID shopId) {
        if (actor == null || actor.getId() == null || shopId == null || !actor.isEnabled()
                || (actor.getRole() != AppRole.USER && actor.getRole() != AppRole.CUSTOMER)) {
            throw denied();
        }
        return actor.getId();
    }

    private void requireActiveVendorShop(ShopEntity shop) {
        if (shop == null || shop.getKind() != ShopKind.VENDOR || shop.getStatus() != ShopStatus.ACTIVE) {
            throw denied();
        }
    }

    private void requireReadableVendorShop(ShopEntity shop) {
        if (shop == null || shop.getKind() != ShopKind.VENDOR
                || (shop.getStatus() != ShopStatus.ACTIVE && shop.getStatus() != ShopStatus.SUSPENDED)) {
            throw denied();
        }
    }

    private void requireActiveOwnerMembership(ShopMembershipEntity membership, UUID shopId, UUID userId) {
        if (!shopId.equals(membership.getShopId()) || !userId.equals(membership.getUserId())
                || membership.getRole() != ShopMembershipRole.OWNER
                || membership.getStatus() != ShopMembershipStatus.ACTIVE
                || membership.getRevokedAt() != null) {
            throw denied();
        }
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException(DENIED);
    }

    /** The already-authorized shop and membership rows for the current operation. */
    public record ShopAccess(ShopEntity shop, ShopMembershipEntity membership) { }
}
