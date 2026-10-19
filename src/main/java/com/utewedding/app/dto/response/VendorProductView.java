package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ProductModerationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Vendor-safe product projection; it exposes workflow status but no admin moderation controls. */
public record VendorProductView(UUID id, Long categoryId, String name, String slug, String sku,
                                String shortDescription, String description, String customizationGuide,
                                long unitPrice, BigDecimal compareAtPrice, int stockQuantity,
                                int minOrderQuantity, Integer weightGrams, String dimensions, String material,
                                int discountPercent, boolean customizable, boolean sellerVisible, boolean active,
                                ProductModerationStatus moderationStatus, String moderationReason,
                                String imageUrl, UUID managedMediaId, Instant updatedAt, long version) { }
