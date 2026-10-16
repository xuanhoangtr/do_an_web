package com.utewedding.app.controller;

import com.utewedding.app.domain.entity.ShippingProviderOperationEntity;
import com.utewedding.app.repository.ShippingProviderOperationRepository;
import com.utewedding.app.repository.ShippingEventRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.ShippingOperationsService;
import com.utewedding.app.service.impl.ShippingOperationPersistence;
import com.utewedding.app.service.impl.ShippingWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/shipping-operations")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminShippingOperationsController {
    private final ShippingProviderOperationRepository operations;
    private final ShippingEventRepository events;
    private final ShippingOperationPersistence persistence;
    private final ShippingOperationsService shippingOperations;
    private final ShippingWebhookService webhookService;

    public AdminShippingOperationsController(ShippingProviderOperationRepository operations,
                                             ShippingEventRepository events,
                                             ShippingOperationPersistence persistence,
                                             ShippingOperationsService shippingOperations,
                                             ShippingWebhookService webhookService) {
        this.operations = operations;
        this.events = events;
        this.persistence = persistence;
        this.shippingOperations = shippingOperations;
        this.webhookService = webhookService;
    }

    @GetMapping("/ambiguous")
    public String ambiguous(Model model) {
        model.addAttribute("operations", operations.findTop100ByStatusOrderByCreatedAtAsc("AMBIGUOUS"));
        model.addAttribute("unmatchedEvents", events.findTop100UnreconciledByOutcomeInOrderByReceivedAtAsc(
                java.util.List.of("UNKNOWN_SHIPMENT", "AMBIGUOUS_SHIPMENT", "REJECTED_ACCOUNT_MISMATCH")));
        return "admin/shipping-ambiguous";
    }

    @PostMapping("/{id}/verify-created")
    public String verifyCreated(@PathVariable UUID id,
                                @RequestParam String providerShipmentId,
                                @AuthenticationPrincipal UserPrincipal actor,
                                HttpServletRequest request,
                                RedirectAttributes flash) {
        try {
            if (providerShipmentId == null || providerShipmentId.isBlank()) {
                throw new IllegalArgumentException("Nhập mã vận đơn GHN cần kiểm tra.");
            }
            shippingOperations.verifyAmbiguousCreated(id, providerShipmentId.trim(), actor.getId(),
                    context(actor, request));
            flash.addFlashAttribute("successMessage", "GHN Detail đã xác nhận mã vận đơn khớp đơn hàng.");
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/admin/shipping-operations/ambiguous";
    }

    @PostMapping("/{id}/check-and-retry")
    public String checkAndRetry(@PathVariable UUID id,
                                @AuthenticationPrincipal UserPrincipal actor,
                                HttpServletRequest request,
                                RedirectAttributes flash) {
        try {
            shippingOperations.checkAmbiguousAndRetry(id, actor.getId(), context(actor, request));
            flash.addFlashAttribute("successMessage",
                    "Đã đối chiếu với GHN; vận đơn được cập nhật nếu tồn tại, hoặc đã xếp hàng thử lại khi GHN xác nhận chưa tạo.");
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/admin/shipping-operations/ambiguous";
    }

    @PostMapping("/events/{id}/reconcile")
    public String reconcileUnknownEvent(@PathVariable UUID id,
                                        @AuthenticationPrincipal UserPrincipal actor,
                                        HttpServletRequest request,
                                        RedirectAttributes flash) {
        try {
            var result = webhookService.reconcileUnknownEvent(id, actor.getId(), context(actor, request));
            if ("APPLIED".equals(result.outcome())) {
                flash.addFlashAttribute("successMessage", "Đã ghép webhook GHN và cập nhật trạng thái vận đơn.");
            } else {
                flash.addFlashAttribute("errorMessage", "Webhook không được áp dụng: " + result.outcome());
            }
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/admin/shipping-operations/ambiguous";
    }

    @PostMapping("/shipments/{id}/track")
    public String trackShipment(@PathVariable UUID id,
                                @AuthenticationPrincipal UserPrincipal actor,
                                HttpServletRequest request,
                                RedirectAttributes flash) {
        try {
            UUID orderId = shippingOperations.requestTrack(id, actor.getId(), context(actor, request));
            flash.addFlashAttribute("successMessage", "Đã xếp hàng tra cứu GHN; trạng thái sẽ được cập nhật khi GHN phản hồi.");
            return "redirect:/admin/orders/" + orderId;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
            return "redirect:/admin/shipping-operations/ambiguous";
        }
    }

    @PostMapping("/shipments/{id}/cancel")
    public String cancelShipment(@PathVariable UUID id,
                                 @AuthenticationPrincipal UserPrincipal actor,
                                 HttpServletRequest request,
                                 RedirectAttributes flash) {
        try {
            UUID orderId = shippingOperations.requestCancel(id, actor.getId(), context(actor, request));
            flash.addFlashAttribute("successMessage", "Đã xếp hàng yêu cầu hủy GHN; kết quả sẽ được cập nhật khi GHN phản hồi.");
            return "redirect:/admin/orders/" + orderId;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
            return "redirect:/admin/shipping-operations/ambiguous";
        }
    }

    private RequestContext context(UserPrincipal user, HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-ID"), user == null ? "ADMIN" : user.getRole().name());
    }
}
