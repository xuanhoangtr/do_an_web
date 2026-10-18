package com.utewedding.app.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Public shop fields only. Contact and legal profile data are deliberately excluded. */
public record PublicShopView(UUID id, String slug, String displayName, String description,
                             String logoUrl, String bannerUrl, Instant joinedAt,
                             long activeProductCount) {
}
