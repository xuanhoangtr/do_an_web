package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ProductModerationStatus;
import java.time.Instant;
import java.util.UUID;

public record ProductModerationView(UUID id, UUID shopId, String shopName, String name, String sku,
                                    String description, String imageUrl, long unitPrice,
                                    ProductModerationStatus moderationStatus, String moderationReason,
                                    Instant updatedAt) { }
