/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  jakarta.servlet.http.HttpServletRequest
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.data.domain.PageRequest
 *  org.springframework.data.domain.Pageable
 *  org.springframework.security.access.prepost.PreAuthorize
 *  org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
 *  org.springframework.security.core.Authentication
 *  org.springframework.stereotype.Controller
 *  org.springframework.ui.Model
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PathVariable
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RequestParam
 *  org.springframework.web.servlet.mvc.support.RedirectAttributes
 */
package com.utewedding.app.controller;

import com.utewedding.app.controller.WebErrorMessages;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.dto.request.AssignFulfillmentRequest;
import com.utewedding.app.dto.response.FulfillmentResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.ShippingOperationsService;
import com.utewedding.app.service.web.AdminUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping(value={"/admin/fulfillments"})
@PreAuthorize(value="hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
public class FulfillmentWebController {
    private final FulfillmentService fulfillmentService;
    @Autowired(required=false)
    private ShippingOperationsService shippingOperations;
    @Autowired(required=false)
    private OrderShippingSnapshotRepository shippingSnapshots;
    @Autowired(required=false)
    private com.utewedding.app.service.impl.ShippingCarrierSnapshotReader shippingCarrierSnapshotReader;

    public FulfillmentWebController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue="") String q, @RequestParam(required=false) FulfillmentStatus status, @RequestParam(required=false) UUID shipperId, @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to, @RequestParam(defaultValue="0") int page, Model model) {
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        var fulfillments = this.fulfillmentService.searchAdmin(q, status, shipperId,
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1L).atStartOfDay(zone).toInstant(),
                (Pageable)PageRequest.of((int)Math.max(page, 0), (int)20));
        model.addAttribute("fulfillments", fulfillments);
        model.addAttribute("shippingCarriers", shippingCarrierSnapshotReader == null ? java.util.Map.of()
                : shippingCarrierSnapshotReader.forOrders(fulfillments.getContent().stream()
                        .map(FulfillmentResponse::orderId).filter(java.util.Objects::nonNull).toList()));
        model.addAttribute("shippers", this.fulfillmentService.listShippers());
        model.addAttribute("q", (Object)q);
        model.addAttribute("status", (Object)(status == null ? "" : status.name()));
        model.addAttribute("shipperId", (Object)(shipperId == null ? "" : shipperId.toString()));
        model.addAttribute("from", (Object)from);
        model.addAttribute("to", (Object)to);
        model.addAttribute("statuses", (Object)FulfillmentStatus.values());
        return "admin/fulfillments";
    }

    @GetMapping(value={"/{id}"})
    public String detail(@PathVariable UUID id, Authentication authentication, Model model) {
        FulfillmentResponse fulfillment = this.fulfillmentService.getForActor(this.currentUserId(authentication), id);
        model.addAttribute("fulfillment", (Object)fulfillment);
        model.addAttribute("shippers", this.fulfillmentService.listShippers());
        model.addAttribute("orderFulfillments", this.fulfillmentService.listForOrder(fulfillment.orderId()));
        model.addAttribute("roleView", (Object)"admin");
        model.addAttribute("shippingCarrierName", shippingCarrierSnapshotReader == null
                ? "Chưa xác định nhà vận chuyển" : shippingCarrierSnapshotReader.forOrder(fulfillment.orderId()).name());
        return "fulfillment/detail";
    }

    @PostMapping(value={"/orders/{orderId}/cod-collector"})
    public String codCollector(@PathVariable UUID orderId, @RequestParam UUID fulfillmentId, Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
        this.fulfillmentService.reassignCodCollector(this.currentUserId(authentication), orderId, fulfillmentId, this.context(request));
        redirect.addFlashAttribute("successMessage", (Object)"\u0110\u00e3 \u0111\u1ed5i \u0111\u1ea7u m\u1ed1i thu COD.");
        return "redirect:/admin/orders/" + String.valueOf(orderId);
    }

    @PostMapping(value={"/{id}/assign"})
    public String assign(@PathVariable UUID id, @RequestParam UUID shipperId, @RequestParam(required=false) String trackingNumber, Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
        this.fulfillmentService.assign(this.currentUserId(authentication), id, new AssignFulfillmentRequest(shipperId, trackingNumber), this.context(request));
        redirect.addFlashAttribute("successMessage", (Object)"\u0110\u00e3 g\u00e1n shipper; m\u00e3 v\u1eadn \u0111\u01a1n \u0111\u01b0\u1ee3c c\u1eadp nh\u1eadt n\u1ebfu b\u1ea1n nh\u1eadp.");
        return "redirect:/admin/fulfillments";
    }

    @PostMapping(value={"/{id}/create-shipment"})
    public String createGhnShipment(@PathVariable UUID id, Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
        UUID orderId = null;
        try {
            UUID actorId = this.currentUserId(authentication);
            FulfillmentResponse fulfillment = this.fulfillmentService.getForActor(actorId, id);
            orderId = fulfillment.orderId();
            if (fulfillment.status() != FulfillmentStatus.PROCESSING) {
                throw new BusinessConflictException("Ch\u1ec9 c\u00f3 th\u1ec3 t\u1ea1o v\u1eadn \u0111\u01a1n sau khi \u0111\u00e3 chu\u1ea9n b\u1ecb xong h\u00e0ng.");
            }
            if (this.shippingSnapshots == null || !this.shippingSnapshots.findByOrderId(orderId).map(snapshot -> "GHN".equalsIgnoreCase(snapshot.getProviderCode())).orElse(false).booleanValue()) {
                throw new BusinessConflictException("\u0110\u01a1n n\u00e0y kh\u00f4ng d\u00f9ng nh\u00e0 v\u1eadn chuy\u1ec3n GHN.");
            }
            if (this.shippingOperations == null) {
                throw new IllegalStateException("Ch\u1ee9c n\u0103ng t\u1ea1o v\u1eadn \u0111\u01a1n GHN ch\u01b0a kh\u1ea3 d\u1ee5ng.");
            }
            this.shippingOperations.requestCreate(id, "GHN", "admin-create-ghn:" + String.valueOf(id), this.context(request));
            redirect.addFlashAttribute("successMessage", (Object)"\u0110\u00e3 ghi nh\u1eadn y\u00eau c\u1ea7u GHN Staging. M\u00e3 v\u1eadn \u0111\u01a1n s\u1ebd hi\u1ec7n sau khi GHN ph\u1ea3n h\u1ed3i; t\u1ea3i l\u1ea1i trang \u0111\u1ec3 c\u1eadp nh\u1eadt.");
        }
        catch (RuntimeException exception) {
            redirect.addFlashAttribute("errorMessage", (Object)WebErrorMessages.forException(exception));
        }
        return orderId == null ? "redirect:/admin/fulfillments" : "redirect:/admin/orders/" + String.valueOf(orderId);
    }

    @PostMapping(value={"/{id}/processing"})
    public String processing(@PathVariable UUID id, Authentication authentication, HttpServletRequest request, @RequestParam(defaultValue="false") boolean returnToOrder, @RequestParam(required=false) UUID returnUserId, @RequestParam(name="returnQuery", defaultValue="") String returnQuery, @RequestParam(required=false, name="returnRole") UserRole returnRole, @RequestParam(required=false, name="returnStatus") UserStatus returnStatus, @RequestParam(name="returnAccounts", defaultValue="ACTIVE") AdminUserFilter.AccountState returnAccounts, @RequestParam(defaultValue="0") int returnPage, @RequestParam(defaultValue="0") int returnOrdersPage, @RequestParam(defaultValue="0") int returnWeddingsPage, RedirectAttributes redirect) {
        UUID actorId = this.currentUserId(authentication);
        FulfillmentResponse fulfillment = returnToOrder ? this.fulfillmentService.getForActor(actorId, id) : null;
        this.fulfillmentService.processAsAdmin(actorId, id, this.context(request));
        redirect.addFlashAttribute("successMessage", (Object)"\u0110\u00e3 chuy\u1ec3n fulfillment platform sang chu\u1ea9n b\u1ecb h\u00e0ng.");
        if (returnToOrder && fulfillment != null) {
            if (returnUserId != null) {
                redirect.addAttribute("returnUserId", (Object)returnUserId);
                redirect.addAttribute("returnQuery", (Object)(returnQuery == null ? "" : returnQuery));
                if (returnRole != null) {
                    redirect.addAttribute("returnRole", (Object)returnRole.name());
                }
                if (returnStatus != null) {
                    redirect.addAttribute("returnStatus", (Object)returnStatus.name());
                }
                redirect.addAttribute("returnAccounts", (Object)(returnAccounts == null ? AdminUserFilter.AccountState.ACTIVE.name() : returnAccounts.name()));
                redirect.addAttribute("returnPage", (Object)Math.max(0, returnPage));
                redirect.addAttribute("returnOrdersPage", (Object)Math.max(0, returnOrdersPage));
                redirect.addAttribute("returnWeddingsPage", (Object)Math.max(0, returnWeddingsPage));
            }
            return "redirect:/admin/orders/" + String.valueOf(fulfillment.orderId());
        }
        return "redirect:/admin/fulfillments";
    }

    private UUID currentUserId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal) {
            UserPrincipal user = (UserPrincipal)principal;
            return user.getId();
        }
        throw new AuthenticationCredentialsNotFoundException("Kh\u00f4ng x\u00e1c \u0111\u1ecbnh \u0111\u01b0\u1ee3c ng\u01b0\u1eddi d\u00f9ng hi\u1ec7n t\u1ea1i.");
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"), request.getHeader("X-Request-Id"));
    }
}
