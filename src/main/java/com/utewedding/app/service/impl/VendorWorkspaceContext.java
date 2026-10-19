package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ShopMembershipRole;
import com.utewedding.app.domain.ShopMembershipStatus;
import com.utewedding.app.domain.entity.ShopMembershipEntity;
import com.utewedding.app.repository.ShopMembershipRepository;
import com.utewedding.app.security.AppRole;
import com.utewedding.app.security.UserPrincipal;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolves the active OWNER's shop scope without trusting a request-supplied shop id. */
@Service
public class VendorWorkspaceContext {
    private static final String ACCESS_DENIED = "Bạn không có quyền truy cập khu vực cửa hàng.";

    private final MarketplaceFeatureFlags features;
    private final ShopMembershipRepository memberships;
    private final ShopAccessPolicy shopAccessPolicy;

    public VendorWorkspaceContext(MarketplaceFeatureFlags features,
                                  ShopMembershipRepository memberships,
                                  ShopAccessPolicy shopAccessPolicy) {
        this.features = features;
        this.memberships = memberships;
        this.shopAccessPolicy = shopAccessPolicy;
    }

    /**
     * Resolves a workspace on every request so a revoked membership is not retained
     * in a browser session or cached navigation model. A suspended shop can resolve
     * its read-only workspace; every mutation must separately pass requireMutation.
     */
    @Transactional(readOnly = true)
    public Workspace requireWorkspace(UserPrincipal actor) {
        if (features == null || !features.isEnabled(Feature.VENDOR_MANAGEMENT)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (actor == null || actor.getId() == null || !actor.isEnabled()
                || (actor.getRole() != AppRole.USER && actor.getRole() != AppRole.CUSTOMER)) {
            throw denied();
        }

        List<ShopMembershipEntity> candidates = memberships.findByUserIdAndRoleAndStatus(
                actor.getId(), ShopMembershipRole.OWNER, ShopMembershipStatus.ACTIVE);
        List<ShopMembershipEntity> activeOwners = candidates == null ? List.of() : candidates.stream()
                .filter(Objects::nonNull)
                .filter(membership -> membership.getShopId() != null
                        && actor.getId().equals(membership.getUserId())
                        && membership.getRole() == ShopMembershipRole.OWNER
                        && membership.getStatus() == ShopMembershipStatus.ACTIVE
                        && membership.getRevokedAt() == null)
                .toList();

        // V1 deliberately supports one owner shop per account. Ambiguous/corrupt
        // membership state must not choose a shop implicitly.
        if (activeOwners.size() != 1) {
            throw denied();
        }

        ShopAccessPolicy.ShopAccess access = shopAccessPolicy.requireRead(actor, activeOwners.getFirst().getShopId());
        return new Workspace(access.shop().getId(), access.shop().getCode(), access.shop().getSlug(),
                access.shop().getDisplayName(), actor.getDisplayName(), actor.getEmail());
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException(ACCESS_DENIED);
    }

    public record Workspace(java.util.UUID shopId, String shopCode, String shopSlug, String shopName,
                            String ownerName, String ownerEmail) { }
}
