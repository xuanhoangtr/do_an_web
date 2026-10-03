package com.utewedding.app.dto.request;

import com.utewedding.app.domain.WishStatus;
import jakarta.validation.constraints.NotNull;

public record ModerateWishRequest(@NotNull WishStatus status) {
}
