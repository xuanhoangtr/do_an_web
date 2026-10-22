package com.utewedding.app.controller;

import com.utewedding.app.domain.entity.ReturnResolutionEntity;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.ReturnResolutionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/returns") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class ReturnResolutionApiController {
    private final ReturnResolutionService service;
    public ReturnResolutionApiController(ReturnResolutionService service){this.service=service;}
    @PostMapping("/{id}/refund") public ReturnResolutionEntity refund(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody Request body,HttpServletRequest request){return service.refund(user.getId(),id,key,body.manualReference(),context(user,request));}
    @PostMapping("/{id}/retry") public ReturnResolutionEntity retry(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,HttpServletRequest request){return service.retry(user.getId(),id,context(user,request));}
    private RequestContext context(UserPrincipal user,HttpServletRequest request){return new RequestContext(request.getRemoteAddr(),request.getHeader("User-Agent"),request.getHeader("X-Request-ID"),user.getRole().name());}
    public record Request(String manualReference){}
}
