package com.utewedding.app.controller;

import com.utewedding.app.dto.request.CreateReturnCaseRequest;
import com.utewedding.app.dto.request.ReturnDisputeRequest;
import com.utewedding.app.dto.request.ReturnShipmentRequest;
import com.utewedding.app.dto.response.ReturnCaseResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ReturnCaseService;
import com.utewedding.app.service.impl.ReturnCaseOperationsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/returns")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class ReturnCaseApiController {
    private final ReturnCaseService service;
    private final ReturnCaseOperationsService operations;
    public ReturnCaseApiController(ReturnCaseService service, ReturnCaseOperationsService operations) { this.service = service; this.operations = operations; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnCaseResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                     @Valid @RequestBody CreateReturnCaseRequest request) {
        return service.create(principal.getId(), key, request);
    }
    @GetMapping
    public List<ReturnCaseResponse> list(@AuthenticationPrincipal UserPrincipal principal) { return service.list(principal.getId()); }
    @GetMapping("/{id}")
    public ReturnCaseResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) { return service.get(principal.getId(), id); }
    @DeleteMapping("/{id}")
    public ReturnCaseResponse cancel(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) { return service.cancel(principal.getId(), id); }

    @PostMapping("/{id}/shipment")
    public ReturnCaseResponse ship(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                   @Valid @RequestBody ReturnShipmentRequest body, jakarta.servlet.http.HttpServletRequest request) {
        return operations.ship(principal.getId(), id, body, request.getHeader("X-Request-Id"));
    }

    @PostMapping("/{id}/disputes")
    public ReturnCaseResponse dispute(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                      @Valid @RequestBody ReturnDisputeRequest body, jakarta.servlet.http.HttpServletRequest request) {
        return operations.openDispute(principal.getId(), id, body, request.getHeader("X-Request-Id"));
    }
}