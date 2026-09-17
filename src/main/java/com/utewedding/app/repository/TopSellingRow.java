package com.utewedding.app.repository;

import java.math.BigDecimal;
import java.util.UUID;

public interface TopSellingRow {
    UUID getProductId();
    String getName();
    String getImageUrl();
    Long getUnitPrice();
    Long getEffectivePrice();
    BigDecimal getAverageRating();
    Integer getReviewCount();
    Integer getStockQuantity();
    Long getSoldQuantity();
}
