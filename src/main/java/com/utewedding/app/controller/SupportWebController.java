package com.utewedding.app.controller;

import com.utewedding.app.domain.*;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.*;
import com.utewedding.app.service.web.FormToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequestMapping("/support") @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class SupportWebController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SupportWebController.class);
    private final ReturnCaseQueryService returnQueries;
    private final SupportService service;
    private final FormToken tokens;
    private final OrderService orders;
    private final SupportWebForms forms;

    public SupportWebController(SupportService service, FormToken tokens, OrderService orders,
                                SupportWebForms forms, ReturnCaseQueryService returnQueries) {
        this.service = service;
        this.tokens = tokens;
        this.orders = orders;
        this.forms = forms;
        this.returnQueries = returnQueries;
    }
    @GetMapping public String list(@AuthenticationPrincipal UserPrincipal user,@RequestParam(required=false)SupportTicketStatus status,@RequestParam(required=false)SupportPriority priority,@RequestParam(defaultValue="0")int page,Model model){model.addAttribute("rows",service.search(user.getId(),false,status,priority,null,null,null,page));model.addAttribute("base","/support");model.addAttribute("staff",false);model.addAttribute("statuses",SupportTicketStatus.values());model.addAttribute("priorities",SupportPriority.values());model.addAttribute("supportChatAutoOpen",true);return "operations/support";}
    @GetMapping("/new")
    public String form(@AuthenticationPrincipal UserPrincipal user,
                       @RequestParam(required = false) UUID orderId,
                       @RequestParam(required = false) UUID returnCaseId,
                       HttpServletRequest request, Model model) {
        List<?> customerOrders;
        try {
            customerOrders = orders.list(user.getId());
        } catch (RuntimeException exception) {
            log.warn("Could not load customer orders for support form user {}", user.getId(), exception);
            customerOrders = List.of();
            model.addAttribute("orderLoadError", "Không thể tải danh sách đơn hàng lúc này. Bạn vẫn có thể gửi yêu cầu hỗ trợ.");
        }
        List<?> returnCases = List.of();
        List<?> fulfillments = List.of();
        if (orderId != null) {
            try {
                returnCases = returnQueries.existing(user.getId(), orderId);
                fulfillments = returnQueries.eligibility(user.getId(), orderId);
            } catch (RuntimeException exception) {
                log.warn("Could not load return or fulfillment references for support form user {}", user.getId(), exception);
                model.addAttribute("referenceLoadError", "Không thể tải thông tin đơn hàng liên quan lúc này.");
            }
        }
        model.addAttribute("orders", customerOrders);
        model.addAttribute("returnCases", returnCases);
        model.addAttribute("fulfillments", fulfillments);
        model.addAttribute("orderId", orderId);
        model.addAttribute("returnCaseId", returnCaseId);
        model.addAttribute("categories", SupportCategory.values());
        model.addAttribute("token", tokens.issue(request, "support:create"));
        model.addAttribute("supportChatAutoOpen", true);
        model.addAttribute("supportChatNew", true);
        model.addAttribute("supportChatOrderId", orderId);
        model.addAttribute("supportChatReturnCaseId", returnCaseId);
        return "operations/support-new";
    }
    @PostMapping public String create(@AuthenticationPrincipal UserPrincipal user,@RequestParam SupportCategory category,@RequestParam String subject,@RequestParam String body,@RequestParam(required=false)UUID orderId,@RequestParam(required=false)UUID returnCaseId,@RequestParam(required=false)UUID fulfillmentId,@RequestParam String token,@RequestParam(required=false)List<MultipartFile> files,HttpServletRequest request,RedirectAttributes flash){try{tokens.verify(request,"support:create",token);service.validateCustomerReferences(user.getId(),new SupportService.CreateTicket(category,subject,body,orderId,returnCaseId,null,fulfillmentId,List.of()));var attachments=forms.uploads(user,null,false,token,files,request);var ticket=service.create(user.getId(),token,new SupportService.CreateTicket(category,subject,body,orderId,returnCaseId,null,fulfillmentId,attachments),forms.context(user,request));tokens.complete(request,token);return "redirect:/support/"+ticket.id();}catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));flash.addFlashAttribute("savedSubject",subject);flash.addFlashAttribute("savedBody",body);return "redirect:/support/new";}}
    @GetMapping("/{id}") public String detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,HttpServletRequest request,Model model){String view=forms.detail(user,id,false,request,model);model.addAttribute("supportChatAutoOpen",true);model.addAttribute("supportChatConversationId",id);return view;}
    @PostMapping("/{id}/replies") public String reply(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String body,@RequestParam String token,@RequestParam(required=false)List<MultipartFile> files,HttpServletRequest request,RedirectAttributes flash){return forms.reply(user,id,false,false,body,token,files,request,flash);}
    @GetMapping("/{id}/attachments/{mediaId}") public String attachment(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@PathVariable UUID mediaId){return "redirect:"+service.signedAttachment(user.getId(),id,mediaId,false);}
}
