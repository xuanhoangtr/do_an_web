package com.utewedding.app.dto.response;

import java.util.UUID;

/** DTO returned to the admin web adapter; the JPA entity never reaches it. */
public record AdminProductResponse(
        UUID id,
        Long categoryId,
        String name,
        long unitPrice,
        int stockQuantity,
        int discountPercent,
        String description,
        String material,
        String imageUrl,
        UUID managedMediaId,
        boolean featured,
        boolean active,
        String sku,
        String shortDescription,
        String customizationGuide,
        java.math.BigDecimal compareAtPrice,
        int minOrderQuantity,
        Integer weightGrams,
        String dimensions,
        boolean customizable) {
}
