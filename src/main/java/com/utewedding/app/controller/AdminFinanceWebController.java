package com.utewedding.app.controller;

import com.utewedding.app.domain.CodReconciliationStatus;
import com.utewedding.app.domain.CodRemittanceStatus;
import com.utewedding.app.domain.ProviderTransactionStatus;
import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.entity.CodObligationEntity;
import com.utewedding.app.domain.entity.CodReconciliationEntity;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.OrderAppliedDiscountRepository;
import com.utewedding.app.repository.CodObligationRepository;
import com.utewedding.app.repository.CodReconciliationRepository;
import com.utewedding.app.repository.PlatformFundReceiptRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.CodReconciliationService;
import com.utewedding.app.service.impl.PlatformFundReceiptService;
import com.utewedding.app.service.web.FormToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
@RequestMapping("/admin/finance")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class AdminFinanceWebController {
    private final CodReconciliationRepository reconciliations;
    private final CodObligationRepository obligations;
    private final CodReconciliationService cod;
    private final FormToken tokens;
    private final WebConfirm confirm;
    private final PaymentRepository payments;
    private final PlatformFundReceiptRepository receipts;
    private final PlatformFundReceiptService platformFundReceipts;
    private final OrderAppliedDiscountRepository appliedDiscounts;

    public AdminFinanceWebController(CodReconciliationRepository reconciliations,
                                     CodObligationRepository obligations, CodReconciliationService cod,
                                     FormToken tokens, WebConfirm confirm, PaymentRepository payments,
                                     PlatformFundReceiptRepository receipts,
                                     PlatformFundReceiptService platformFundReceipts,
                                     OrderAppliedDiscountRepository appliedDiscounts) {
        this.reconciliations = reconciliations;
        this.obligations = obligations;
        this.cod = cod;
        this.tokens = tokens;
        this.confirm = confirm;
        this.payments = payments;
        this.receipts = receipts;
        this.platformFundReceipts = platformFundReceipts;
        this.appliedDiscounts = appliedDiscounts;
    }

    @GetMapping
    public String home() {
        return "redirect:/admin/finance/cod";
    }

    @GetMapping("/receipts")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String gatewayReceipts(@RequestParam(defaultValue = "0") int page,
                                  HttpServletRequest request, Model model) {
        var candidates = payments.findGatewayReceiptCandidates(ProviderTransactionType.CAPTURE,
                ProviderTransactionStatus.SUCCEEDED, PageRequest.of(Math.max(0, page), 20));
        Map<String, String> receiptTokens = new HashMap<>();
        for (var candidate : candidates) {
            receiptTokens.put(candidate.paymentId().toString(),
                    tokens.issue(request, "finance:receipt:gateway:" + candidate.paymentId()));
        }
        model.addAttribute("candidates", candidates);
        model.addAttribute("receiptTokens", receiptTokens);
        var groupedCandidates = platformFundReceipts.groupedGatewayReceiptCandidates(
                PageRequest.of(Math.max(0, page), 20));
        Map<String, String> groupedReceiptTokens = new HashMap<>();
        for (var candidate : groupedCandidates) {
            groupedReceiptTokens.put(candidate.captureTransactionId().toString(),
                    tokens.issue(request, "finance:receipt:group-gateway:" + candidate.captureTransactionId()));
        }
        model.addAttribute("groupedCandidates", groupedCandidates);
        model.addAttribute("groupedReceiptTokens", groupedReceiptTokens);
        model.addAttribute("receipts", receipts.findTop50ByOrderByReceivedAtDesc());
        model.addAttribute("page", page);
        model.addAttribute("receivedAtDefault", LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .withSecond(0).withNano(0).toString());
        var subsidyCandidates = appliedDiscounts.findPlatformMerchandiseSubsidies(
                VoucherFundingSource.PLATFORM, VoucherScope.PLATFORM,
                java.util.List.of(VoucherType.PERCENT, VoucherType.FIXED),
                PageRequest.of(Math.max(0, page), 20));
        Map<String, String> subsidyTokens = new HashMap<>();
        for (var candidate : subsidyCandidates) {
            subsidyTokens.put(candidate.orderId().toString(),
                    tokens.issue(request, "finance:receipt:subsidy:" + candidate.orderId()));
        }
        model.addAttribute("subsidyCandidates", subsidyCandidates);
        model.addAttribute("subsidyTokens", subsidyTokens);
        model.addAttribute("subsidyPage", page);
        var carrierRemittanceCandidates = platformFundReceipts.carrierRemittanceCandidates();
        Map<String, String> carrierRemittanceTokens = new HashMap<>();
        for (var candidate : carrierRemittanceCandidates) {
            carrierRemittanceTokens.put(candidate.getShipmentId().toString(),
                    tokens.issue(request, "finance:receipt:carrier:" + candidate.getShipmentId()));
        }
        model.addAttribute("carrierRemittanceCandidates", carrierRemittanceCandidates);
        model.addAttribute("carrierRemittanceTokens", carrierRemittanceTokens);
        return "operations/finance-receipts";
    }

    @PostMapping("/receipts/gateway/{paymentId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String recordGatewayReceipt(@AuthenticationPrincipal UserPrincipal user,
                                       @PathVariable UUID paymentId,
                                       @RequestParam String providerAccountReference,
                                       @RequestParam String receivedAt,
                                       @RequestParam long feeAmount,
                                       @RequestParam long deductionAmount,
                                       @RequestParam String externalReference,
                                       @RequestParam String evidenceReference,
                                       @RequestParam String evidenceNote,
                                       @RequestParam String token,
                                       @RequestParam(defaultValue = "false") boolean confirmed,
                                       HttpServletRequest request, Model model, RedirectAttributes flash) {
        try {
            Map<String, String> fields = Map.of(
                    "providerAccountReference", providerAccountReference,
                    "receivedAt", receivedAt,
                    "feeAmount", Long.toString(feeAmount),
                    "deductionAmount", Long.toString(deductionAmount),
                    "externalReference", externalReference,
                    "evidenceReference", evidenceReference,
                    "evidenceNote", evidenceNote);
            if (!confirm.ready(request, model, "finance:receipt:gateway:" + paymentId, token, confirmed,
                    fields, "Xác nhận biên nhận cổng thanh toán",
                    paymentId + " · " + providerAccountReference)) {
                return "operations/confirm";
            }
            platformFundReceipts.recordGatewaySettlement(user.getId(), paymentId,
                    providerAccountReference,
                    LocalDateTime.parse(receivedAt).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                    feeAmount, deductionAmount,
                    externalReference, evidenceReference, evidenceNote,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-ID"), user.getRole().name()));
            flash.addFlashAttribute("successMessage", "Đã ghi nhận biên nhận đối soát cổng thanh toán.");
        } catch (RuntimeException error) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(error));
        }
        return "redirect:/admin/finance/receipts";
    }

    @PostMapping("/receipts/group-gateway/{captureTransactionId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String recordGroupedGatewayReceipt(@AuthenticationPrincipal UserPrincipal user,
                                              @PathVariable UUID captureTransactionId,
                                              @RequestParam String providerAccountReference,
                                              @RequestParam String receivedAt,
                                              @RequestParam long feeAmount,
                                              @RequestParam long deductionAmount,
                                              @RequestParam String externalReference,
                                              @RequestParam String evidenceReference,
                                              @RequestParam String evidenceNote,
                                              @RequestParam String token,
                                              @RequestParam(defaultValue = "false") boolean confirmed,
                                              HttpServletRequest request, Model model, RedirectAttributes flash) {
        try {
            Map<String, String> fields = Map.of(
                    "providerAccountReference", providerAccountReference,
                    "receivedAt", receivedAt,
                    "feeAmount", Long.toString(feeAmount),
                    "deductionAmount", Long.toString(deductionAmount),
                    "externalReference", externalReference,
                    "evidenceReference", evidenceReference,
                    "evidenceNote", evidenceNote);
            if (!confirm.ready(request, model, "finance:receipt:group-gateway:" + captureTransactionId,
                    token, confirmed, fields, "Xác nhận biên nhận capture nhóm",
                    captureTransactionId + " · " + providerAccountReference)) {
                return "operations/confirm";
            }
            platformFundReceipts.recordGroupedGatewaySettlement(user.getId(), captureTransactionId,
                    providerAccountReference,
                    LocalDateTime.parse(receivedAt).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                    feeAmount, deductionAmount, externalReference, evidenceReference, evidenceNote,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-ID"), user.getRole().name()));
            flash.addFlashAttribute("successMessage", "Đã ghi nhận một biên nhận cho capture nhóm và phân bổ theo đơn con.");
        } catch (RuntimeException error) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(error));
        }
        return "redirect:/admin/finance/receipts";
    }

    @PostMapping("/receipts/carrier/{shipmentId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String recordCarrierRemittance(@AuthenticationPrincipal UserPrincipal user,
                                          @PathVariable UUID shipmentId,
                                          @RequestParam long reportedCodAmount,
                                          @RequestParam String receivedAt,
                                          @RequestParam long feeAmount,
                                          @RequestParam long deductionAmount,
                                          @RequestParam String externalReference,
                                          @RequestParam String evidenceReference,
                                          @RequestParam String evidenceNote,
                                          @RequestParam String token,
                                          @RequestParam(defaultValue = "false") boolean confirmed,
                                          HttpServletRequest request, Model model, RedirectAttributes flash) {
        try {
            Map<String, String> fields = Map.of("reportedCodAmount", Long.toString(reportedCodAmount),
                    "receivedAt", receivedAt,
                    "feeAmount", Long.toString(feeAmount),
                    "deductionAmount", Long.toString(deductionAmount),
                    "externalReference", externalReference,
                    "evidenceReference", evidenceReference,
                    "evidenceNote", evidenceNote);
            if (!confirm.ready(request, model, "finance:receipt:carrier:" + shipmentId, token, confirmed,
                    fields, "Xác nhận biên nhận COD từ GHN",
                    shipmentId + " · phí " + feeAmount + " · khấu trừ " + deductionAmount)) {
                return "operations/confirm";
            }
            var outcome = cod.recordCarrierRemittance(user.getId(), shipmentId, reportedCodAmount,
                    LocalDateTime.parse(receivedAt).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                    feeAmount, deductionAmount, externalReference, evidenceReference, evidenceNote,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-ID"), user.getRole().name()));
            if (outcome.hasAmountDiscrepancy()) {
                flash.addFlashAttribute("successMessage", "Đã lưu số COD thực thu theo sao kê GHN. Số tiền lệch "
                        + "nghĩa vụ đã lưu nên được giữ để đối soát; chưa tạo biên nhận nhận tiền và chưa đủ điều kiện chi trả.");
            } else {
                flash.addFlashAttribute("successMessage", "Đã ghi nhận biên nhận COD GHN có bằng chứng.");
            }
        } catch (RuntimeException error) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(error));
        }
        return "redirect:/admin/finance/receipts";
    }

    @PostMapping("/receipts/platform-subsidy/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String recordPlatformSubsidy(@AuthenticationPrincipal UserPrincipal user,
                                        @PathVariable UUID orderId,
                                        @RequestParam String receivedAt,
                                        @RequestParam String externalReference,
                                        @RequestParam String evidenceReference,
                                        @RequestParam String evidenceNote,
                                        @RequestParam String token,
                                        @RequestParam(defaultValue = "false") boolean confirmed,
                                        HttpServletRequest request, Model model, RedirectAttributes flash) {
        try {
            Map<String, String> fields = Map.of("receivedAt", receivedAt,
                    "externalReference", externalReference,
                    "evidenceReference", evidenceReference,
                    "evidenceNote", evidenceNote);
            if (!confirm.ready(request, model, "finance:receipt:subsidy:" + orderId, token, confirmed,
                    fields, "Xác nhận quỹ nền tảng tài trợ voucher",
                    orderId + " · " + receivedAt)) {
                return "operations/confirm";
            }
            platformFundReceipts.recordPlatformSubsidy(user.getId(), orderId,
                    LocalDateTime.parse(receivedAt).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                    externalReference, evidenceReference, evidenceNote,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-ID"), user.getRole().name()));
            flash.addFlashAttribute("successMessage", "Đã ghi nhận nguồn trợ giá nền tảng có bằng chứng.");
        } catch (RuntimeException error) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(error));
        }
        return "redirect:/admin/finance/receipts";
    }

    @GetMapping("/cod")
    public String cod(@RequestParam(defaultValue = "") String status,
                      @RequestParam(defaultValue = "0") int page,
                      HttpServletRequest request, Model model) {
        CodReconciliationStatus selected = status.isBlank() ? null : CodReconciliationStatus.valueOf(status);
        Specification<CodReconciliationEntity> filter = (root, query, builder) -> selected == null
                ? builder.conjunction() : builder.equal(root.get("status"), selected);
        var rows = reconciliations.findAll(filter, PageRequest.of(Math.max(0, page), 20,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        Map<String, String> rowTokens = new HashMap<>();
        for (CodReconciliationEntity row : rows) {
            if (row.getStatus() == CodReconciliationStatus.REVIEW_REQUIRED
                    || row.getStatus() == CodReconciliationStatus.AWAITING_AMOUNT) {
                rowTokens.put(row.getOrderId().toString(),
                        tokens.issue(request, "finance:cod:" + row.getOrderId() + ":resolve"));
            }
        }
        var remittances = obligations.findTop100ByRemittanceStatusOrderByCollectedAtAsc(
                CodRemittanceStatus.PENDING);
        Map<String, String> remittanceTokens = new HashMap<>();
        for (CodObligationEntity obligation : remittances) {
            remittanceTokens.put(obligation.getFulfillmentId().toString(),
                    tokens.issue(request, "finance:cod:remittance:" + obligation.getFulfillmentId()));
        }
        model.addAttribute("rows", rows);
        model.addAttribute("remittances", remittances);
        model.addAttribute("kind", "cod");
        model.addAttribute("status", status);
        model.addAttribute("statuses", CodReconciliationStatus.values());
        model.addAttribute("rowTokens", rowTokens);
        model.addAttribute("remittanceTokens", remittanceTokens);
        return "operations/finance";
    }

    @PostMapping("/cod/remittances/{fulfillmentId}/receive")
    public String receiveCodFromShipper(@AuthenticationPrincipal UserPrincipal user,
                                        @PathVariable UUID fulfillmentId,
                                        @RequestParam long receivedAmount,
                                        @RequestParam(required = false, defaultValue = "") String reference,
                                        @RequestParam String note, @RequestParam String token,
                                        @RequestParam(defaultValue = "false") boolean confirmed,
                                        HttpServletRequest request, Model model, RedirectAttributes flash) {
        try {
            Map<String, String> fields = Map.of("receivedAmount", Long.toString(receivedAmount),
                    "reference", reference, "note", note);
            if (!confirm.ready(request, model, "finance:cod:remittance:" + fulfillmentId, token, confirmed,
                    fields, "Xác nhận đã nhận tiền COD từ shipper",
                    fulfillmentId + " · " + receivedAmount + " VND")) {
                return "operations/confirm";
            }
            cod.receiveRemittance(user.getId(), fulfillmentId, receivedAmount, reference, note,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-ID"), user.getRole().name()));
            flash.addFlashAttribute("successMessage", "Đã ghi nhận shop nhận tiền COD từ shipper.");
        } catch (RuntimeException error) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(error));
        }
        return "redirect:/admin/finance/cod";
    }

    @PostMapping("/cod/{orderId}/resolve")
    public String resolveCod(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID orderId,
                             @RequestParam long collectedAmount, @RequestParam String reference,
                             @RequestParam String note, @RequestParam String token,
                             @RequestParam(defaultValue = "false") boolean confirmed,
                             @RequestParam(defaultValue = "false") boolean returnToOrder,
                             HttpServletRequest request, Model model, RedirectAttributes flash) {
        try {
            Map<String, String> fields = Map.of("collectedAmount", Long.toString(collectedAmount),
                    "reference", reference, "note", note,
                    "returnToOrder", Boolean.toString(returnToOrder));
            if (!confirm.ready(request, model, "finance:cod:" + orderId + ":resolve", token, confirmed,
                    fields, "Xác nhận đối soát COD", orderId + " · " + collectedAmount + " VND")) {
                return "operations/confirm";
            }
            cod.resolve(user.getId(), orderId, collectedAmount, reference, note,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-ID"), user.getRole().name()));
            flash.addFlashAttribute("successMessage", "Đã đối soát COD.");
        } catch (RuntimeException error) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(error));
        }
        return returnToOrder ? "redirect:/admin/orders/" + orderId : "redirect:/admin/finance/cod";
    }
}
