package com.utewedding.app.dto.response;

import java.math.BigDecimal;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TopSellingProductResponse(UUID productId, String name, String imageUrl,
                                        long unitPrice, BigDecimal averageRating,
                                        int reviewCount, int stockQuantity,
                                        long soldQuantity, long effectivePrice) {

    /** Compatibility alias for clients that consume the original price field. */
    @JsonProperty("price")
    public long price() { return unitPrice; }
}
