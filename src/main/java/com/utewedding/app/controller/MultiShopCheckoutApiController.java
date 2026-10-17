package com.utewedding.app.controller;

import com.utewedding.app.dto.request.MultiShopCheckoutRequest;
import com.utewedding.app.dto.response.MultiShopCheckoutPreviewResponse;
import com.utewedding.app.dto.response.MultiShopCheckoutResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** New grouped checkout endpoints; existing single-order API routes remain unchanged. */
@RestController
public class MultiShopCheckoutApiController {
    private final OrderService orders;

    public MultiShopCheckoutApiController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/api/checkout/preview")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public MultiShopCheckoutPreviewResponse preview(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MultiShopCheckoutRequest request) {
        return orders.previewGrouped(principal.getId(), request);
    }

    @PostMapping("/api/checkout/place")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public MultiShopCheckoutResponse place(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MultiShopCheckoutRequest request,
            HttpServletRequest httpRequest) {
        return orders.placeGrouped(principal.getId(), request, context(httpRequest));
    }

    @GetMapping("/api/checkout-groups/{groupId}")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER','ADMIN','SUPER_ADMIN')")
    public MultiShopCheckoutResponse get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID groupId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return orders.getCheckoutGroup(principal.getId(), groupId);
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
