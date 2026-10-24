package com.utewedding.app.controller;

import com.utewedding.app.domain.RiskEventStatus;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.RiskService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/risk-events")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class AdminRiskController {
    private final RiskService service;
    public AdminRiskController(RiskService service) { this.service = service; }

    @GetMapping
    public Page<RiskService.EventView> search(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required=false) RiskEventStatus status, @RequestParam(required=false) String rule,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
            @RequestParam(defaultValue="0") int page) {
        return service.search(user.getId(),status,rule,from,to,page);
    }
    @GetMapping("/{id}")
    public RiskService.EventView detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable java.util.UUID id) {
        return service.detail(user.getId(),id);
    }
    @PostMapping("/{id}/resolve")
    public RiskService.EventView resolve(@AuthenticationPrincipal UserPrincipal user,@PathVariable java.util.UUID id,
            @RequestBody Resolution body,HttpServletRequest request) {
        return service.view(service.resolve(user.getId(),id,body.status(),body.note(),
                new RequestContext(request.getRemoteAddr(),request.getHeader("User-Agent"),
                        request.getHeader("X-Request-ID"),user.getRole().name())));
    }
    public record Resolution(RiskEventStatus status,String note) {}
}
