package com.utewedding.app.controller;

import com.utewedding.app.domain.*;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.SupportService;
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

@Controller @RequestMapping("/admin/support") @PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class AdminSupportWebController {
    private final SupportService service;private final SupportWebForms forms;private final FormToken tokens;
    public AdminSupportWebController(SupportService service,SupportWebForms forms,FormToken tokens){this.service=service;this.forms=forms;this.tokens=tokens;}
    @GetMapping public String list(@AuthenticationPrincipal UserPrincipal user,@RequestParam(required=false)SupportTicketStatus status,@RequestParam(required=false)SupportPriority priority,@RequestParam(required=false)SupportCategory category,@RequestParam(required=false)UUID assignee,@RequestParam(defaultValue="false")boolean mine,@RequestParam(defaultValue="false")boolean unassigned,@RequestParam(defaultValue="true")boolean needsReply,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page,@RequestParam(required=false)UUID conversationId,Model model){model.addAttribute("rows",service.staffSearch(user.getId(),status,priority,mine?user.getId():assignee,category,q,unassigned,needsReply,page));model.addAttribute("base","/admin/support");model.addAttribute("staff",true);model.addAttribute("needsReply",needsReply);model.addAttribute("initialQuery",q);model.addAttribute("initialStatus",status);model.addAttribute("initialMine",mine);model.addAttribute("initialUnassigned",unassigned);model.addAttribute("initialPage",page);model.addAttribute("supportChatConversationId",conversationId);return "operations/support-chat-inbox";}
    @GetMapping("/{id}") public String detail(@PathVariable UUID id){return "redirect:/admin/support?conversationId="+id;}
    @PostMapping("/{id}/replies") public String reply(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam String body,@RequestParam(defaultValue="false")boolean internal,@RequestParam String token,@RequestParam(required=false)List<MultipartFile> files,HttpServletRequest request,RedirectAttributes flash){return forms.reply(user,id,true,internal,body,token,files,request,flash);}
    @PostMapping("/{id}/change") public String change(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestParam(required=false)SupportTicketStatus status,@RequestParam(required=false)SupportPriority priority,@RequestParam(defaultValue="false")boolean claim,@RequestParam String token,HttpServletRequest request,RedirectAttributes flash){try{tokens.verify(request,"support:"+id+":change",token);service.change(user.getId(),id,token,new SupportService.Change(status,priority,claim?user.getId():null),forms.context(user,request));tokens.complete(request,token);flash.addFlashAttribute("successMessage","Đã cập nhật ticket.");}catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));}return "redirect:/admin/support/"+id;}
    @GetMapping("/{id}/attachments/{mediaId}") public String attachment(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@PathVariable UUID mediaId){return "redirect:"+service.signedAttachment(user.getId(),id,mediaId,true);}
}
