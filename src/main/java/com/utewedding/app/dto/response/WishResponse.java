package com.utewedding.app.dto.response;

import com.utewedding.app.domain.WishStatus;
import java.time.Instant;
import java.util.UUID;

public record WishResponse(UUID id, WishStatus status, Instant approvedAt) {
}
