package com.utewedding.app.controller;

import com.utewedding.app.domain.ReturnCaseStatus;
import com.utewedding.app.dto.request.*;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.*;
import com.utewedding.app.service.web.FormToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequestMapping("/returns") @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class ReturnWebController {
    private final ReturnCaseService cases;private final ReturnCaseQueryService query;private final ReturnCaseOperationsService operations;
    private final ReturnWebForms forms;private final FormToken tokens;
    public ReturnWebController(ReturnCaseService cases,ReturnCaseQueryService query,ReturnCaseOperationsService operations,ReturnWebForms forms,FormToken tokens){this.cases=cases;this.query=query;this.operations=operations;this.forms=forms;this.tokens=tokens;}
    @GetMapping public String list(@AuthenticationPrincipal UserPrincipal user,@RequestParam(required=false)ReturnCaseStatus status,@RequestParam(defaultValue="0")int page,Model model){return forms.list(user,"customer",status,null,null,null,false,page,model);}
    @GetMapping("/new") public String createForm(@AuthenticationPrincipal UserPrincipal user,@RequestParam UUID orderId,@RequestParam(required=false)UUID fulfillmentId,HttpServletRequest request,Model model){
        model.addAttribute("eligibility",query.eligibility(user.getId(),orderId));model.addAttribute("orderId",orderId);model.addAttribute("selectedFulfillment",fulfillmentId);model.addAttribute("token",tokens.issue(request,"returns:create"));return "operations/return-new";
    }
    @PostMapping public String create(@AuthenticationPrincipal UserPrincipal user,@RequestParam UUID orderId,@RequestParam UUID fulfillmentId,@RequestParam String reason,@RequestParam List<UUID> itemId,@RequestParam List<Integer> quantity,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){
        try {tokens.verify(request,"returns:create",token);var selected=ReturnWebForms.quantities(itemId,quantity).stream().filter(i->i.quantity()>0).map(i->new CreateReturnCaseRequest.Item(i.id(),i.quantity())).toList();var result=cases.create(user.getId(),token,new CreateReturnCaseRequest(fulfillmentId,reason,selected));tokens.complete(request,token);return "redirect:/returns/"+result.id();}
        catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));flash.addFlashAttribute("savedReason",reason);return "redirect:/returns/new?orderId="+orderId+"&fulfillmentId="+fulfillmentId;}
    }
    @GetMapping("/{id}") public String detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,HttpServletRequest request,Model model){return forms.detail(user,"customer",id,request,model);}
    @PostMapping("/{id}/cancel") public String cancel(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){return forms.run(request,"customer",id,"cancel",token,()->cases.cancel(user.getId(),id),flash);}
    @PostMapping("/{id}/shipment") public String shipment(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String carrier,@RequestParam String trackingNumber,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){return forms.run(request,"customer",id,"shipment",token,()->operations.ship(user.getId(),id,new ReturnShipmentRequest(carrier,trackingNumber),request.getHeader("X-Request-ID")),flash);}
    @PostMapping("/{id}/disputes") public String dispute(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String reason,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){return forms.run(request,"customer",id,"disputes",token,()->operations.openDispute(user.getId(),id,new ReturnDisputeRequest(reason),request.getHeader("X-Request-ID")),flash);}
}
