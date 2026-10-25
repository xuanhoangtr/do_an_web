package com.utewedding.app.controller;

import com.utewedding.app.domain.entity.CodReconciliationEntity;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.CodReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FinanceController {
    private final CodReconciliationService cod;

    public FinanceController(CodReconciliationService cod) {
        this.cod = cod;
    }

    @PostMapping("/api/v1/admin/finance/cod/{orderId}/resolve")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
    public CodReconciliationEntity resolveCod(@AuthenticationPrincipal UserPrincipal user,
                                               @PathVariable UUID orderId,
                                               @RequestBody CodResolutionRequest body,
                                               HttpServletRequest request) {
        return cod.resolve(user.getId(), orderId, body.collectedAmount(), body.reference(), body.note(),
                new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                        request.getHeader("X-Request-ID"), user.getRole().name()));
    }

    public record CodResolutionRequest(long collectedAmount, String reference, String note) { }
}
