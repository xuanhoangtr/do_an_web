package com.utewedding.app.controller;

import com.utewedding.app.integration.MoMoService;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/momo")
public class MoMoController {
    private final MoMoService momo;
    public MoMoController(MoMoService momo) { this.momo = momo; }
    @PostMapping("/create/{orderId}")
    public com.utewedding.app.service.port.PaymentProvider.PaymentRequest create(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID orderId) {
        return momo.create(principal.getId(), orderId);
    }
    @PostMapping("/ipn")
    public com.utewedding.app.service.port.PaymentProvider.PaymentResult ipn(
            @RequestParam Map<String, String> parameters, HttpServletRequest request) {
        return momo.callback(parameters, context(request));
    }
    @PostMapping(value = "/ipn", consumes = MediaType.APPLICATION_JSON_VALUE)
    public com.utewedding.app.service.port.PaymentProvider.PaymentResult ipnJson(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> parameters,
            HttpServletRequest request) {
        return momo.callback(parameters, context(request));
    }
    @GetMapping("/return")
    public com.utewedding.app.service.port.PaymentProvider.PaymentResult paymentReturn(@RequestParam Map<String, String> parameters) {
        return new com.utewedding.app.service.port.PaymentProvider.PaymentResult(
                momo.verifySignature(parameters), parameters.getOrDefault("resultCode", "99"),
                parameters.getOrDefault("message", "Payment callback received"));
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
