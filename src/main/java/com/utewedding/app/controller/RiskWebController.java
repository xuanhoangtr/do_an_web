package com.utewedding.app.controller;

import com.utewedding.app.domain.RiskEventStatus;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.RiskService;
import com.utewedding.app.service.web.FormToken;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequestMapping("/admin/risk-events") @PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class RiskWebController {
    private final RiskService service;private final FormToken tokens;
    public RiskWebController(RiskService service,FormToken tokens){this.service=service;this.tokens=tokens;}
    @GetMapping public String list(@AuthenticationPrincipal UserPrincipal user,@RequestParam(required=false)RiskEventStatus status,@RequestParam(defaultValue="")String rule,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="0")int page,Model model){model.addAttribute("rows",service.search(user.getId(),status,rule,from,to,page));model.addAttribute("statuses",RiskEventStatus.values());return "operations/risk";}
    @GetMapping("/{id}") public String detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,HttpServletRequest request,Model model){model.addAttribute("event",service.detail(user.getId(),id));model.addAttribute("statuses",RiskEventStatus.values());model.addAttribute("token",tokens.issue(request,"risk:"+id));return "operations/risk-detail";}
    @PostMapping("/{id}/resolve") public String resolve(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam RiskEventStatus status,@RequestParam String note,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){try{tokens.verify(request,"risk:"+id,token);service.resolve(user.getId(),id,status,note,new RequestContext(request.getRemoteAddr(),request.getHeader("User-Agent"),request.getHeader("X-Request-ID"),user.getRole().name()));flash.addFlashAttribute("successMessage","Đã xử lý cảnh báo.");}catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));}return "redirect:/admin/risk-events/"+id;}
}
