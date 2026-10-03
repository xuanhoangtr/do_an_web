package com.utewedding.app.dto.response;

import java.util.UUID;

/** Public guestbook response without exposing the JPA Wish entity. */
public record WishCreatedResponse(UUID id, String status, String message) {
}
