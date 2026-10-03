package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public guestbook submission request. */
public record SubmitWishRequest(
        @NotBlank @Size(max = 160) String senderName,
        @NotBlank @Size(max = 2000) String message) {
}
