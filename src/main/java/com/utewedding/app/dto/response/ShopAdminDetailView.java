package com.utewedding.app.dto.response;

import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.ShopStatusHistoryEntity;
import com.utewedding.app.domain.ShopMembershipStatus;
import java.time.Instant;
import java.util.List;

/** Data required for an administrator to review and manage one vendor shop. */
public record ShopAdminDetailView(
        ShopEntity shop,
        String ownerName,
        String ownerEmail,
        ShopMembershipStatus ownerMembershipStatus,
        Instant ownerMembershipRevokedAt,
        String ownerMembershipRevocationReason,
        List<ShopApplicationAttemptView> applications,
        List<ShopStatusHistoryEntity> history) { }
