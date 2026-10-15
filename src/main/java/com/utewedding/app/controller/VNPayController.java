package com.utewedding.app.controller;

import com.utewedding.app.integration.VNPayService;
import com.utewedding.app.service.web.CurrentUserContext;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** VNPay redirect and server-to-server callback endpoints. */
@RestController
@RequestMapping("/api/v1/payments/vnpay")
public class VNPayController {

    private final VNPayService vnpayService;
    private final CurrentUserContext currentUser;

    public VNPayController(VNPayService vnpayService, CurrentUserContext currentUser) {
        this.vnpayService = vnpayService;
        this.currentUser = currentUser;
    }

    @PostMapping({"/create", "/create-payment"})
    public VNPayService.PaymentUrl createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest,
            Authentication authentication) {
        UUID userId = currentUser.requireUserId(authentication);
        String ip = request.clientIp();
        if (ip == null || ip.isBlank()) {
            ip = httpRequest.getRemoteAddr();
        }
        return vnpayService.createPaymentUrl(
                userId, request.orderNumber(), request.orderInfo(), ip);
    }

    @RequestMapping(
            value = {"/ipn", "/vnpay_ipn"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public VNPayService.IpnResponse ipn(@RequestParam Map<String, String> parameters,
                                       HttpServletRequest httpRequest) {
        return vnpayService.handleIpn(parameters, context(httpRequest));
    }

    @GetMapping(value = "/return", produces = MediaType.APPLICATION_JSON_VALUE)
    public VNPayService.ReturnResult paymentReturn(
            @RequestParam Map<String, String> parameters) {
        return vnpayService.inspectReturn(parameters);
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }

    public record CreatePaymentRequest(
            @NotBlank String orderNumber,
            String orderInfo,
            String clientIp) {
    }
}
