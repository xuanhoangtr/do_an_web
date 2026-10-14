package com.utewedding.app.service;

import com.utewedding.app.dto.request.AddCartItemRequest;
import com.utewedding.app.dto.request.UpdateCartItemRequest;
import com.utewedding.app.dto.response.CartResponse;
import java.util.UUID;

public interface CartService {
    CartResponse getCart(UUID userId);
    CartResponse addItem(UUID userId, AddCartItemRequest request);
    CartResponse updateQuantity(UUID userId, UpdateCartItemRequest request);
    CartResponse removeItem(UUID userId, UUID productId);
}
