package com.utewedding.app.controller;

import com.utewedding.app.domain.*;
import com.utewedding.app.dto.request.ReturnDisputeDecisionRequest;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequestMapping("/admin/returns") @PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class AdminReturnWebController {
    @org.springframework.beans.factory.annotation.Autowired private ReturnExchangeService exchanges;
    private final ReturnWebForms forms;private final ReturnCaseOperationsService operations;
    private final ReturnResolutionService resolutions;private final ReturnCaseQueryService query;private final WebConfirm confirm;
    public AdminReturnWebController(ReturnWebForms forms,ReturnCaseOperationsService operations,ReturnResolutionService resolutions,ReturnCaseQueryService query,WebConfirm confirm){this.forms=forms;this.operations=operations;this.resolutions=resolutions;this.query=query;this.confirm=confirm;}
    @GetMapping public String list(@AuthenticationPrincipal UserPrincipal user,@RequestParam(required=false)ReturnCaseStatus status,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="false")boolean disputed,@RequestParam(defaultValue="0")int page,Model model){return forms.list(user,"admin",status,from,to,q,disputed,page,model);}
    @GetMapping("/{id}") public String detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,HttpServletRequest request,Model model){return forms.detail(user,"admin",id,request,model);}
    @PostMapping({"/{id}/claim","/{id}/review","/{id}/receive","/{id}/inspect"})
    public String operation(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam(required=false)List<UUID> itemId,@RequestParam(required=false)List<Integer> quantity,@RequestParam(defaultValue="true")boolean approve,@RequestParam(defaultValue="true")boolean returnRequired,@RequestParam(defaultValue="")String reason,@RequestParam(defaultValue="NO_RESTOCK")StockDisposition disposition,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){String action=request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1);return forms.operation(user,"admin",id,action,itemId,quantity,approve,returnRequired,reason,disposition,token,request,flash);}
    @PostMapping("/{id}/disputes/{disputeId}/decision") public String decision(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@PathVariable UUID disputeId,@RequestParam boolean upheld,@RequestParam String decision,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){return forms.run(request,"admin",id,"decision",token,()->operations.decideDispute(user.getId(),id,disputeId,new ReturnDisputeDecisionRequest(upheld,decision)),flash);}
    @PostMapping("/{id}/close") public String close(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){return forms.run(request,"admin",id,"close",token,()->{operations.close(user.getId(),id,request.getHeader("X-Request-ID"));return null;},flash);}
    @PostMapping("/{id}/shipment/delivered") public String shipmentDelivered(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){return forms.run(request,"admin",id,"shipment/delivered",token,()->{operations.markShipmentDelivered(user.getId(),id);return null;},flash);}
    @PostMapping({"/{id}/exchange","/{id}/exchange/delivered"}) @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String exchange(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String token,@RequestParam(defaultValue="")String carrier,@RequestParam(defaultValue="")String trackingNumber,@RequestParam(defaultValue="false")boolean confirmed,HttpServletRequest request,Model model,RedirectAttributes flash){String action=request.getRequestURI().endsWith("/delivered")?"exchange/delivered":"exchange";try{var detail=query.detail(user.getId(),"admin",id);if(!confirm.ready(request,model,"admin:"+id+":"+action,token,confirmed,Map.of("carrier",carrier,"trackingNumber",trackingNumber),"Xác nhận đổi hàng",detail.caseData().getCaseNumber()+" · "+carrier+" · "+trackingNumber))return "operations/confirm";var context=new RequestContext(request.getRemoteAddr(),request.getHeader("User-Agent"),request.getHeader("X-Request-ID"),user.getRole().name());if(action.equals("exchange"))exchanges.exchange(user.getId(),id,token,carrier,trackingNumber,context);else exchanges.delivered(user.getId(),id,context);flash.addFlashAttribute("successMessage","Đã cập nhật đổi hàng.");}catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));}return "redirect:/admin/returns/"+id;}
    @PostMapping({"/{id}/refund","/{id}/retry"}) @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String refund(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String token,@RequestParam(defaultValue="")String manualReference,@RequestParam(defaultValue="false")boolean confirmed,HttpServletRequest request,Model model,RedirectAttributes flash){
        String action=request.getRequestURI().endsWith("/retry")?"retry":"refund";
        try {var detail=query.detail(user.getId(),"admin",id);if(!confirm.ready(request,model,"admin:"+id+":"+action,token,confirmed,Map.of("manualReference",manualReference),"Xác nhận hoàn tiền",detail.maximumRefund()+" VND · "+detail.caseData().getCaseNumber()))return "operations/confirm";
            var context=new RequestContext(request.getRemoteAddr(),request.getHeader("User-Agent"),request.getHeader("X-Request-ID"),user.getRole().name());
            if(action.equals("retry"))resolutions.retry(user.getId(),id,context);else resolutions.refund(user.getId(),id,token,manualReference,context);
            flash.addFlashAttribute("successMessage","Đã xử lý hoàn tiền.");
        }catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));}
        return "redirect:/admin/returns/"+id;
    }
}
