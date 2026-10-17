package com.utewedding.app.controller;

import com.utewedding.app.dto.response.MultiShopCheckoutResponse;
import com.utewedding.app.integration.VNPayService;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.payment.PaymentIntegrityService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Group-owned VNPAY session and unpaid group cancellation operations. */
@RestController
public class CheckoutGroupPaymentApiController {
    private final VNPayService vnpay;
    private final PaymentIntegrityService payments;
    private final OrderService orders;

    public CheckoutGroupPaymentApiController(VNPayService vnpay, PaymentIntegrityService payments,
                                             OrderService orders) {
        this.vnpay = vnpay;
        this.payments = payments;
        this.orders = orders;
    }

    @PostMapping("/api/checkout-groups/{groupId}/payment-session")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public VNPayService.GroupPaymentSessionResult createPaymentSession(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID groupId,
            HttpServletRequest request) {
        return vnpay.createGroupPaymentSession(principal.getId(), groupId, request.getRemoteAddr(),
                context(request, principal));
    }

    @PostMapping("/api/checkout-groups/{groupId}/cancel")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public MultiShopCheckoutResponse cancelUnpaidGroup(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID groupId,
            HttpServletRequest request) {
        boolean cancelled = payments.cancelUnpaidVnpayGroup(principal.getId(), groupId,
                context(request, principal));
        if (!cancelled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nhóm checkout đã được cập nhật; tải lại trạng thái trước khi tiếp tục.");
        }
        return orders.getCheckoutGroup(principal.getId(), groupId);
    }

    private static RequestContext context(HttpServletRequest request, UserPrincipal principal) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-ID"), principal.getRole().name());
    }
}
