package com.utewedding.app.controller;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.dto.response.FulfillmentResponse;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.web.CurrentUserContext;
import com.utewedding.app.service.web.DashboardRange;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/shipper")
@PreAuthorize("hasRole('SHIPPER')")
public class ShipperWebController {
    private final FulfillmentService fulfillmentService;
    private final CurrentUserContext currentUser;
    private OrderShippingSnapshotRepository shippingSnapshots;

    public ShipperWebController(FulfillmentService fulfillmentService, CurrentUserContext currentUser) {
        this.fulfillmentService = fulfillmentService;
        this.currentUser = currentUser;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachOrderShippingSnapshotRepository(OrderShippingSnapshotRepository repository) {
        shippingSnapshots = repository;
    }

    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/shipper/board";
    }

    @GetMapping("/board")
    public String board(Authentication authentication,
                        @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
                        @RequestParam(defaultValue = "work") String tab,
                        @RequestParam(defaultValue = "") String q,
                        @RequestParam(defaultValue = "") String status,
                        Model model) {
        boolean history = "history".equalsIgnoreCase(tab);
        String selectedTab = history ? "history" : "work";
        String search = q == null ? "" : q.trim();
        if (search.length() > 100) search = search.substring(0, 100);
        FulfillmentStatus selectedStatus = parseStatus(status);
        if (!history && selectedStatus == FulfillmentStatus.DELIVERED) selectedStatus = null;
        UUID shipperId = currentUser.requireUserId(authentication);
        var now = LocalDate.now(DashboardRange.DEFAULT_ZONE);
        var todayStart = now.atStartOfDay(DashboardRange.DEFAULT_ZONE).toInstant();
        var tomorrowStart = now.plusDays(1).atStartOfDay(DashboardRange.DEFAULT_ZONE).toInstant();

        var fulfillments = fulfillmentService.listForShipperBoard(
                shipperId, history, search, selectedStatus, pageable);
        model.addAttribute("fulfillments", fulfillments);
        model.addAttribute("shippingProviderCodes", shippingProviderCodes(fulfillments.getContent()));
        model.addAttribute("summary", fulfillmentService.shipperBoardSummary(shipperId, todayStart, tomorrowStart));
        model.addAttribute("performance", fulfillmentService.shipperPerformance(shipperId));
        model.addAttribute("activeTab", selectedTab);
        model.addAttribute("searchQuery", search);
        model.addAttribute("selectedStatus", selectedStatus == null ? "" : selectedStatus.name());
        return "shipper/board";
    }

    private FulfillmentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            FulfillmentStatus value = FulfillmentStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
            return value == FulfillmentStatus.PROCESSING || value == FulfillmentStatus.PICKUP_PENDING
                    || value == FulfillmentStatus.PICKED_UP || value == FulfillmentStatus.IN_TRANSIT
                    || value == FulfillmentStatus.DELIVERED || value == FulfillmentStatus.DELIVERY_FAILED
                    || value == FulfillmentStatus.RETRY_DELIVERY || value == FulfillmentStatus.RETURNING
                    || value == FulfillmentStatus.RETURNED ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @PostMapping("/fulfillments/{id}/shipping")
    public String shipping(@PathVariable UUID id, Authentication authentication,
                           HttpServletRequest request, RedirectAttributes redirect) {
        try {
            fulfillmentService.startShipping(currentUser.requireUserId(authentication), id, context(request));
            redirect.addFlashAttribute("successMessage", "Đã chuyển đơn sang trạng thái đang giao.");
            return "redirect:/shipper/board";
        } catch (com.utewedding.app.exception.BusinessConflictException exception) {
            redirect.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
            return "redirect:/shipper/fulfillments/" + id;
        }
    }

    @PostMapping("/fulfillments/{id}/pickup")
    public String confirmPickup(@PathVariable UUID id, Authentication authentication,
                                HttpServletRequest request, RedirectAttributes redirect) {
        fulfillmentService.confirmPickup(currentUser.requireUserId(authentication), id, context(request));
        redirect.addFlashAttribute("successMessage", "Đã xác nhận nhận hàng từ kho.");
        return "redirect:/shipper/fulfillments/" + id;
    }

    @GetMapping("/fulfillments/{id}")
    public String detail(@PathVariable UUID id, Authentication authentication, Model model) {
        FulfillmentResponse fulfillment = fulfillmentService.getForActor(
                currentUser.requireUserId(authentication), id);
        model.addAttribute("fulfillment", fulfillment);
        model.addAttribute("shippingProviderCode", shippingProviderCode(fulfillment.orderId()));
        model.addAttribute("roleView", "shipper");
        model.addAttribute("failureReasons", com.utewedding.app.domain.DeliveryFailureReason.values());
        return "fulfillment/detail";
    }

    private Map<UUID, String> shippingProviderCodes(List<FulfillmentResponse> fulfillments) {
        Map<UUID, String> codes = new LinkedHashMap<>();
        if (shippingSnapshots == null || fulfillments == null) return codes;
        for (FulfillmentResponse fulfillment : fulfillments) {
            if (fulfillment.orderId() == null || codes.containsKey(fulfillment.orderId())) continue;
            String code = shippingProviderCode(fulfillment.orderId());
            if (code != null) codes.put(fulfillment.orderId(), code);
        }
        return codes;
    }

    private String shippingProviderCode(UUID orderId) {
        if (shippingSnapshots == null || orderId == null) return null;
        return shippingSnapshots.findByOrderId(orderId)
                .map(com.utewedding.app.domain.entity.OrderShippingSnapshotEntity::getProviderCode)
                .orElse(null);
    }

    @PostMapping("/fulfillments/{id}/failed")
    public String failed(@PathVariable UUID id, @RequestParam String reasonCode,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) String nextAttemptAt,
                         @RequestPart(value = "proof", required = false) MultipartFile proof,
                         Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
        fulfillmentService.failDelivery(currentUser.requireUserId(authentication), id, reasonCode, note,
                parseWhen(nextAttemptAt), proof, context(request));
        redirect.addFlashAttribute("successMessage", "CUSTOMER_REFUSED".equals(reasonCode)
                ? "Đã ghi nhận khách từ chối nhận. Kiện hàng chuyển sang hoàn về kho; chỉ chốt hoàn khi shipper xác nhận đã trả hàng."
                : "Đã ghi nhận giao thất bại. Shipper có thể hẹn giao lại hoặc bắt đầu hoàn hàng.");
        return "redirect:/shipper/fulfillments/" + id;
    }

    @PostMapping("/fulfillments/{id}/retry")
    public String retry(@PathVariable UUID id, @RequestParam(required = false) String nextAttemptAt,
                        Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
        fulfillmentService.retryDelivery(currentUser.requireUserId(authentication), id, parseWhen(nextAttemptAt),
                context(request));
        redirect.addFlashAttribute("successMessage", "Đã chuyển lại trạng thái đang giao.");
        return "redirect:/shipper/fulfillments/" + id;
    }

    @PostMapping("/fulfillments/{id}/return")
    public String startReturn(@PathVariable UUID id, @RequestParam(required = false) String note,
                              Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
        fulfillmentService.startReturn(currentUser.requireUserId(authentication), id, note, context(request));
        redirect.addFlashAttribute("successMessage", "Đã chuyển sang hoàn hàng.");
        return "redirect:/shipper/fulfillments/" + id;
    }

    @PostMapping("/fulfillments/{id}/returned")
    public String returned(@PathVariable UUID id, @RequestPart("proof") MultipartFile proof,
                           @RequestParam(required = false) String note,
                           Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
        fulfillmentService.completeReturn(currentUser.requireUserId(authentication), id, proof, note, context(request));
        redirect.addFlashAttribute("successMessage", "Đã xác nhận hoàn hàng.");
        return "redirect:/shipper/fulfillments/" + id;
    }

    @PostMapping("/fulfillments/{id}/delivered")
    public String delivered(@PathVariable UUID id, @RequestPart("proof") MultipartFile proof,
                            @RequestParam(required = false) Long collectedAmount,
                            @RequestParam(required = false) String collectionReference,
                            Authentication authentication, HttpServletRequest request,
                            RedirectAttributes redirect) {
        fulfillmentService.deliver(currentUser.requireUserId(authentication), id, proof,
                collectedAmount, collectionReference, context(request));
        redirect.addFlashAttribute("successMessage", "Đã xác nhận giao hàng thành công.");
        return "redirect:/shipper/fulfillments/" + id;
    }

    private Instant parseWhen(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
