package com.utewedding.app.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ProductReviewMediaResponse(UUID id, String url, String mimeType,
                                         long byteSize, Instant createdAt) {
}
