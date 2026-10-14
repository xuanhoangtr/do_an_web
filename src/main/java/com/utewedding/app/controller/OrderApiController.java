package com.utewedding.app.controller;

import com.utewedding.app.dto.request.CreateOrderRequest;
import com.utewedding.app.dto.request.PaymentRequest;
import com.utewedding.app.dto.response.OrderResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class OrderApiController {

    private final OrderService orderService;

    public OrderApiController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                @Valid @RequestBody CreateOrderRequest request,
                                HttpServletRequest httpRequest) {
        return orderService.create(principal.getId(), request, context(httpRequest));
    }

    @GetMapping
    public List<OrderResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return orderService.list(principal.getId());
    }

    @GetMapping("/page")
    public Page<OrderResponse> page(@AuthenticationPrincipal UserPrincipal principal,
                                    @PageableDefault(size = 20, sort = "createdAt",
                                            direction = org.springframework.data.domain.Sort.Direction.DESC)
                                    Pageable pageable) {
        return orderService.listPage(principal.getId(), pageable);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal UserPrincipal principal,
                             @PathVariable UUID orderId) {
        return orderService.get(principal.getId(), orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable UUID orderId,
                                HttpServletRequest httpRequest) {
        return orderService.cancel(principal.getId(), orderId, context(httpRequest));
    }

    @PostMapping("/{orderId}/refund")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public OrderResponse refund(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable UUID orderId,
                                @Valid @RequestBody PaymentRequest request,
                                HttpServletRequest httpRequest) {
        return orderService.refund(principal.getId(), orderId, request, context(httpRequest));
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
