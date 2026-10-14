package com.utewedding.app.service.web;

import java.util.UUID;

public interface WebCartService {
    WebViewModels.CartView get(UUID userId);
    WebViewModels.CartView add(UUID userId, UUID productId, int quantity, String customizationText);
    default WebViewModels.CartView add(UUID userId, UUID productId, int quantity) {
        return add(userId, productId, quantity, null);
    }
    WebViewModels.CartView update(UUID userId, UUID cartItemId, String action);
    WebViewModels.CartView remove(UUID userId, UUID cartItemId);
}
