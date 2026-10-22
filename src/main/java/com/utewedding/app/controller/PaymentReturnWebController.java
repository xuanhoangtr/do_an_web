package com.utewedding.app.controller;

import com.utewedding.app.integration.MoMoService;
import com.utewedding.app.integration.VNPayService;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.service.web.CurrentUserContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import jakarta.servlet.http.HttpServletRequest;
import com.utewedding.app.service.RequestContext;

/** Browser return pages. Provider IPN endpoints stay on the JSON API. */
@Controller
public class PaymentReturnWebController {
    private final VNPayService vnpayService;
    private final MoMoService momoService;
    private final OrderRepository orders;
    private final CurrentUserContext currentUser;
    private final com.utewedding.app.service.payment.PaymentStatusRefreshService paymentRefresh;

    public PaymentReturnWebController(VNPayService vnpayService, MoMoService momoService,
                                      OrderRepository orders, CurrentUserContext currentUser,
                                      com.utewedding.app.service.payment.PaymentStatusRefreshService paymentRefresh) {
        this.vnpayService = vnpayService;
        this.momoService = momoService;
        this.orders = orders;
        this.currentUser = currentUser;
        this.paymentRefresh = paymentRefresh;
    }

    @GetMapping("/payments/vnpay/return")
    public String vnpay(@RequestParam Map<String, String> parameters, Authentication authentication,
                        HttpServletRequest request, Model model) {
        var result = vnpayService.inspectReturn(parameters);
        if (result.validSignature() && result.savedSession() && result.providerReference() != null
                && !result.orderPaid()) {
            paymentRefresh.refreshVnpayReference(result.providerReference(), requestContext(request));
            result = vnpayService.inspectReturn(parameters);
        }
        if (result.validSignature() && result.savedSession() && result.groupId() != null) {
            return "redirect:/checkout-groups/" + result.groupId() + "/success";
        }
        return render(model, "VNPay", result.validSignature(), result.responseCode(), result.message(),
                result.orderNumber(), result.orderStatus(), authentication,
                result.savedSession(), result.attemptStatus());
    }

    @GetMapping("/payments/momo/return")
    public String momo(@RequestParam Map<String, String> parameters, Authentication authentication, Model model) {
        boolean valid = momoService.verifySignature(parameters);
        String code = valid ? parameters.getOrDefault("resultCode", "99") : "99";
        String orderNumber = valid ? parameters.get("orderId") : null;
        String status = "UNKNOWN";
        if (orderNumber != null && !orderNumber.isBlank()) {
            status = orders.findByOrderNumber(orderNumber).map(order -> order.getStatus().name()).orElse("NOT_FOUND");
        }
        return render(model, "MoMo", valid, code,
            valid ? parameters.getOrDefault("message", "Đã nhận kết quả thanh toán")
                : "Không thể xác thực kết quả thanh toán.",
                orderNumber, status, authentication, false, null);
    }

    private String render(Model model, String provider, boolean valid, String code, String message,
                          String orderNumber, String orderStatus, Authentication authentication,
                          boolean savedVnpaySession,
                          com.utewedding.app.domain.PaymentAttemptStatus attemptStatus) {
        model.addAttribute("provider", provider);
        model.addAttribute("signatureValid", valid);
        model.addAttribute("responseCode", code);
        boolean trusted = valid;
        boolean gatewaySuccess = trusted && ("VNPay".equals(provider) ? "00".equals(code) : "0".equals(code));
        model.addAttribute("message", !trusted ? "Không thể xác thực kết quả thanh toán."
                : "PAID".equals(orderStatus) ? "Thanh toán đã được xác nhận."
                : "VNPay".equals(provider) && !savedVnpaySession
                    ? "Không tìm thấy phiên thanh toán đã lưu cho kết quả này. Đơn hàng chưa được xác nhận."
                : gatewaySuccess ? ("VNPay".equals(provider)
                        ? "Đang xác minh thanh toán với VNPAY."
                        : "Đang chờ MoMo xác nhận giao dịch với hệ thống.")
                : "Chưa có xác nhận cuối cùng từ cổng thanh toán. Bạn có thể thử lại từ chi tiết đơn hàng.");
        model.addAttribute("orderNumber", trusted ? orderNumber : null);
        model.addAttribute("orderStatus", trusted ? orderStatus : "UNKNOWN");
        model.addAttribute("paid", trusted && "PAID".equals(orderStatus));
        model.addAttribute("awaitingPayment", false);
        UUID userId = null;
        try {
            userId = currentUser.requireUserId(authentication);
        } catch (RuntimeException ignored) {
            userId = null;
        }
        if (trusted && userId != null && orderNumber != null) {
            var ownOrder = orders.findByOrderNumberAndUserId(orderNumber, userId);
            if (ownOrder.isPresent()) {
                var order = ownOrder.get();
                model.addAttribute("orderId", order.getId());
                if (isPaid(order.getStatus(), order.getPaymentStatus())) {
                    return "redirect:/order/success/" + order.getId();
                }
                boolean awaiting = "VNPay".equals(provider) && savedVnpaySession
                        && order.getStatus() != null && !order.getStatus().isTerminal()
                        && order.getPaymentStatus() != PaymentStatus.FAILED
                        && (attemptStatus == null || attemptStatus.isOpen());
                model.addAttribute("awaitingPayment", awaiting);
                model.addAttribute("paymentAttemptStatus", attemptStatus == null ? "UNKNOWN" : attemptStatus.name());
                if (awaiting) {
                    model.addAttribute("message", "Đang xác minh thanh toán với VNPAY.");
                } else if ("VNPay".equals(provider)
                        && (attemptStatus == com.utewedding.app.domain.PaymentAttemptStatus.FAILED
                            || attemptStatus == com.utewedding.app.domain.PaymentAttemptStatus.EXPIRED)
                        && order.getStatus() != null && !order.getStatus().isTerminal()) {
                    model.addAttribute("message", "Phiên VNPAY này chưa thanh toán thành công. Bạn có thể thử lại từ chi tiết đơn hàng.");
                }
            }
        }
        return "payments/result";
    }

    @GetMapping("/orders/{orderId}/payment-status")
    @ResponseBody
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public org.springframework.http.ResponseEntity<PaymentState> paymentStatus(
            @PathVariable UUID orderId, Authentication authentication, HttpServletRequest request) {
        var order = orders.findByIdAndUserId(orderId, currentUser.requireUserId(authentication))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng."));
        paymentRefresh.refreshPending(order, requestContext(request));
        order = orders.findByIdAndUserId(orderId, currentUser.requireUserId(authentication)).orElseThrow();
        boolean paid = isPaid(order.getStatus(), order.getPaymentStatus());
        boolean terminal = paid || (order.getStatus() != null && order.getStatus().isTerminal())
                || order.getPaymentStatus() == PaymentStatus.FAILED;
        var attemptStatus = paymentRefresh.latestVnpayAttemptStatus(orderId);
        if (attemptStatus == com.utewedding.app.domain.PaymentAttemptStatus.FAILED
                || attemptStatus == com.utewedding.app.domain.PaymentAttemptStatus.EXPIRED) terminal = true;
        PaymentState body = new PaymentState(paid, terminal, paid ? "/order/success/" + orderId : null,
                paid ? "Thanh toán đã được xác nhận."
                        : terminal ? "Phiên thanh toán đã kết thúc. Bạn có thể thử lại từ chi tiết đơn hàng."
                        : "Đang chờ xác nhận thanh toán. Trang sẽ tự chuyển khi hoàn tất.",
                order.getStatus() == null ? "UNKNOWN" : order.getStatus().name(),
                order.getPaymentStatus() == null ? "UNKNOWN" : order.getPaymentStatus().name(),
                attemptStatus == null ? "UNKNOWN" : attemptStatus.name());
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }

    private static boolean isPaid(OrderStatus status, PaymentStatus paymentStatus) {
        return status == OrderStatus.PAID || paymentStatus == PaymentStatus.SUCCEEDED;
    }

    private static RequestContext requestContext(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-ID"), "CUSTOMER");
    }

    public record PaymentState(boolean paid, boolean terminal, String redirectUrl, String message,
                               String orderStatus, String paymentStatus, String paymentAttemptStatus) { }
}
