package com.utewedding.app.controller;

import com.utewedding.app.dto.request.ReturnInspectionRequest;
import com.utewedding.app.dto.request.ReturnReceiveRequest;
import com.utewedding.app.dto.request.ReturnReviewRequest;
import com.utewedding.app.dto.request.ReturnDisputeDecisionRequest;
import com.utewedding.app.dto.response.ReturnCaseResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ReturnCaseOperationsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/returns")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class AdminReturnCaseApiController {
    private final ReturnCaseOperationsService operations;
    public AdminReturnCaseApiController(ReturnCaseOperationsService operations) { this.operations = operations; }
    @PostMapping("/{id}/claim")
    public ReturnCaseResponse claim(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, HttpServletRequest request) {
        return operations.claim(principal.getId(), id, request.getHeader("X-Request-Id"));
    }
    @PostMapping("/{id}/review")
    public ReturnCaseResponse review(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                     @Valid @RequestBody ReturnReviewRequest body, HttpServletRequest request) {
        return operations.review(principal.getId(), id, body, request.getHeader("X-Request-Id"));
    }
    @PostMapping("/{id}/receive")
    public ReturnCaseResponse receive(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                      @Valid @RequestBody ReturnReceiveRequest body, HttpServletRequest request) {
        return operations.receive(principal.getId(), id, body, request.getHeader("X-Request-Id"));
    }
    @PostMapping("/{id}/inspect")
    public ReturnCaseResponse inspect(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                      @Valid @RequestBody ReturnInspectionRequest body, HttpServletRequest request) {
        return operations.inspect(principal.getId(), id, body, request.getHeader("X-Request-Id"));
    }

    @PostMapping("/{id}/shipment/delivered")
    public void shipmentDelivered(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        operations.markShipmentDelivered(principal.getId(), id);
    }

    @PostMapping("/{id}/disputes/{disputeId}/decision")
    public ReturnCaseResponse decideDispute(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                             @PathVariable UUID disputeId, @Valid @RequestBody ReturnDisputeDecisionRequest body) {
        return operations.decideDispute(principal.getId(), id, disputeId, body);
    }
}