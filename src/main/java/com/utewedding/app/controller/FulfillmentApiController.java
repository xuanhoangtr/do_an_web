package com.utewedding.app.controller;

import com.utewedding.app.dto.request.AssignFulfillmentRequest;
import com.utewedding.app.dto.response.FulfillmentResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FulfillmentApiController {
    private final FulfillmentService fulfillmentService;
    public FulfillmentApiController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping("/api/v1/admin/fulfillments")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public Page<FulfillmentResponse> adminList(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return fulfillmentService.listAdmin(pageable);
    }

    @PostMapping("/api/v1/admin/fulfillments/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public FulfillmentResponse assign(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable UUID id,
                                      @Valid @RequestBody AssignFulfillmentRequest request,
                                      HttpServletRequest servletRequest) {
        return fulfillmentService.assign(principal.getId(), id, request, context(servletRequest));
    }

    @GetMapping("/api/v1/admin/shippers")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public List<com.utewedding.app.dto.response.ShipperResponse> shippers() {
        return fulfillmentService.listShippers();
    }

    @PostMapping("/api/v1/admin/fulfillments/{id}/processing")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public FulfillmentResponse processAsAdmin(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable UUID id,
                                              HttpServletRequest servletRequest) {
        return fulfillmentService.processAsAdmin(principal.getId(), id, context(servletRequest));
    }

    @GetMapping("/api/v1/shipper/fulfillments")
    @PreAuthorize("hasRole('SHIPPER')")
    public Page<FulfillmentResponse> shipperList(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return fulfillmentService.listForShipper(principal.getId(), pageable);
    }

    @PostMapping("/api/v1/shipper/fulfillments/{id}/shipping")
    @PreAuthorize("hasRole('SHIPPER')")
    public FulfillmentResponse startShipping(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable UUID id,
                                             HttpServletRequest servletRequest) {
        return fulfillmentService.startShipping(principal.getId(), id, context(servletRequest));
    }

    @PostMapping(value = "/api/v1/shipper/fulfillments/{id}/delivered",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SHIPPER')")
    public FulfillmentResponse delivered(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable UUID id,
                                         @RequestPart("proof") MultipartFile proof,
                                         @RequestPart(value = "collectedAmount", required = false) Long collectedAmount,
                                         @RequestPart(value = "collectionReference", required = false) String collectionReference,
                                         HttpServletRequest servletRequest) {
        return fulfillmentService.deliver(principal.getId(), id, proof, collectedAmount,
                collectionReference, context(servletRequest));
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
