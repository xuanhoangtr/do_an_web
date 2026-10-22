package com.utewedding.app.controller;

import com.utewedding.app.domain.*;
import com.utewedding.app.dto.request.*;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.*;
import com.utewedding.app.service.web.FormToken;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Component
public class ReturnWebForms {
    private final ReturnCaseQueryService query;private final ReturnCaseOperationsService operations;private final FormToken tokens;
    public ReturnWebForms(ReturnCaseQueryService query,ReturnCaseOperationsService operations,FormToken tokens){this.query=query;this.operations=operations;this.tokens=tokens;}
    public static String base(String scope){return scope.equals("customer")?"/returns":"/"+scope+"/returns";}
    public String list(UserPrincipal user,String scope,ReturnCaseStatus status,Instant from,Instant to,String q,boolean disputed,int page,Model model){model.addAttribute("rows",query.list(user.getId(),scope,status,from,to,q,disputed,page));model.addAttribute("base",base(scope));model.addAttribute("scope",scope);model.addAttribute("statuses",ReturnCaseStatus.values());return "operations/returns";}
    public String detail(UserPrincipal user,String scope,UUID id,HttpServletRequest request,Model model){var detail=query.detail(user.getId(),scope,id);var actions=new LinkedHashSet<>(detail.allowedActions());boolean refund=user.getAuthorities().stream().anyMatch(a->Set.of("ROLE_ADMIN","ROLE_SUPER_ADMIN").contains(a.getAuthority()));if(!refund){actions.remove("refund");actions.remove("retry");actions.remove("exchange");actions.remove("exchange/delivered");}var formTokens=new LinkedHashMap<String,String>();for(String action:actions)formTokens.put(action,tokens.issue(request,scope+":"+id+":"+action));model.addAttribute("detail",detail);model.addAttribute("actions",actions);model.addAttribute("tokens",formTokens);model.addAttribute("scope",scope);model.addAttribute("base",base(scope));model.addAttribute("dispositions",StockDisposition.values());return "operations/return-detail";}
    public String run(HttpServletRequest request,String scope,UUID id,String action,String token,Supplier<?> work,RedirectAttributes flash){try{tokens.verify(request,scope+":"+id+":"+action,token);work.get();flash.addFlashAttribute("successMessage","Đã cập nhật yêu cầu.");}catch(RuntimeException error){flash.addFlashAttribute("errorMessage",WebErrorMessages.forException(error));}return "redirect:"+base(scope)+"/"+id;}
    public String operation(UserPrincipal user,String scope,UUID id,String action,List<UUID> ids,List<Integer> quantity,boolean approve,boolean returnRequired,String reason,StockDisposition disposition,String token,HttpServletRequest request,RedirectAttributes flash){
        return run(request,scope,id,action,token,()->switch(action){
            case "claim"->operations.claim(user.getId(),id,request.getHeader("X-Request-ID"));
            case "review"->operations.review(user.getId(),id,new ReturnReviewRequest(approve,returnRequired,quantities(ids,quantity).stream().map(i->new ReturnReviewRequest.Item(i.id(),i.quantity())).toList(),reason),request.getHeader("X-Request-ID"));
            case "receive"->operations.receive(user.getId(),id,new ReturnReceiveRequest(quantities(ids,quantity).stream().map(i->new ReturnReceiveRequest.Item(i.id(),i.quantity())).toList()),request.getHeader("X-Request-ID"));
            case "inspect"->operations.inspect(user.getId(),id,new ReturnInspectionRequest(approve,reason,quantities(ids,quantity).stream().map(i->new ReturnInspectionRequest.Item(i.id(),i.quantity(),disposition)).toList()),request.getHeader("X-Request-ID"));
            default->throw new IllegalArgumentException("Thao tác không hợp lệ.");},flash);
    }
    public static List<Quantity> quantities(List<UUID> ids,List<Integer> quantities){if(ids==null||quantities==null||ids.size()!=quantities.size()||ids.size()>100)throw new IllegalArgumentException("Danh sách sản phẩm không hợp lệ.");var result=new ArrayList<Quantity>();for(int i=0;i<ids.size();i++){if(quantities.get(i)<0)throw new IllegalArgumentException("Số lượng không hợp lệ.");result.add(new Quantity(ids.get(i),quantities.get(i)));}return result;}
    public record Quantity(UUID id,int quantity){}
}
