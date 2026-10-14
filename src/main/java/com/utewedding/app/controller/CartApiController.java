package com.utewedding.app.controller;

import com.utewedding.app.dto.request.AddCartItemRequest;
import com.utewedding.app.dto.request.UpdateCartItemRequest;
import com.utewedding.app.dto.response.CartResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.CartService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JSON cart API. Cart ownership always comes from the authenticated principal. */
@RestController
@RequestMapping("/api/v1/cart")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class CartApiController {

    private final CartService cartService;

    public CartApiController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal UserPrincipal principal) {
        return cartService.getCart(principal.getId());
    }

    @PostMapping("/items")
    public CartResponse addItem(@AuthenticationPrincipal UserPrincipal principal,
                                @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(principal.getId(), request);
    }

    @PutMapping("/items")
    public CartResponse updateItem(@AuthenticationPrincipal UserPrincipal principal,
                                   @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateQuantity(principal.getId(), request);
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable UUID productId) {
        return cartService.removeItem(principal.getId(), productId);
    }
}
