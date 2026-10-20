package com.utewedding.app.controller;

import com.utewedding.app.domain.*;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.*;
import com.utewedding.app.service.web.FormToken;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Component
public class SupportWebForms {
    @org.springframework.beans.factory.annotation.Autowired private OperationsQueryService query;
    private final SupportService service;private final ManagedMediaService media;private final FormToken tokens;
    public SupportWebForms(SupportService service,ManagedMediaService media,FormToken tokens){this.service=service;this.media=media;this.tokens=tokens;}
    public RequestContext context(UserPrincipal user,HttpServletRequest request){return new RequestContext(request.getRemoteAddr(),request.getHeader("User-Agent"),request.getHeader("X-Request-ID"),user.getRole().name());}
    public String detail(UserPrincipal user,UUID id,boolean staff,HttpServletRequest request,Model model){var ticket=staff?service.staffView(user.getId(),id):service.customerView(user.getId(),id);model.addAttribute("ticket",ticket);if(staff)model.addAttribute("auditTrail",query.auditTrail(user.getId(),"SupportTicket",id));model.addAttribute("staff",staff);model.addAttribute("base",staff?"/admin/support":"/support");model.addAttribute("token",tokens.issue(request,"support:"+id+":reply"));model.addAttribute("changeToken",tokens.issue(request,"support:"+id+":change"));model.addAttribute("statuses",SupportTicketStatus.values());model.addAttribute("priorities",SupportPriority.values());boolean reply=ticket.status()!=SupportTicketStatus.CLOSED;if(ticket.status()==SupportTicketStatus.RESOLVED&&!staff){var saved=service.customerMayReopen(user.getId(),id);reply=saved;}model.addAttribute("canReply",reply);return "operations/support-detail";}
    @SuppressWarnings("unchecked") public List<UUID> uploads(UserPrincipal user,UUID ticketId,boolean staff,String token,List<MultipartFile> files,HttpServletRequest request){
        if(files!=null&&files.size()>5)throw new IllegalArgumentException("Tối đa 5 tệp đính kèm.");
        synchronized(request.getSession()){String key="supportUploads."+token;var cached=(List<UUID>)request.getSession().getAttribute(key);if(cached!=null)return cached;if(files==null||files.stream().allMatch(MultipartFile::isEmpty))return List.of();var ids=new ArrayList<UUID>();for(var file:files)if(!file.isEmpty()){var upload=ticketId==null?media.upload(file,user.getId(),"SUPPORT_ATTACHMENT",Duration.ofDays(365)):service.uploadAttachment(user.getId(),ticketId,file,staff);ids.add(upload.getId());}request.getSession().setAttribute(key,List.copyOf(ids));return ids;}
    }
    public String reply(UserPrincipal user,UUID id,boolean staff,boolean internal,String body,String token,List<MultipartFile> files,HttpServletRequest request,RedirectAttributes flash){try{tokens.verify(request,"support:"+id+":reply",token);var attachments=uploads(user,id,staff,token,files,request);service.reply(user.getId(),id,token,new SupportService.Reply(body,staff&&internal,attachments),staff,context(user,request));tokens.complete(request,token);flash.addFlashAttribute("successMessage","Đã gửi phản hồi.");}catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));flash.addFlashAttribute("savedBody",body);}return "redirect:"+(staff?"/admin/support/":"/support/")+id;}
}
