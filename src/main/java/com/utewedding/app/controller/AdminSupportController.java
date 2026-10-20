package com.utewedding.app.controller;

import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.SupportService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/admin/support/tickets")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class AdminSupportController {
 private final SupportService service;public AdminSupportController(SupportService service){this.service=service;}
 @GetMapping public List<SupportService.TicketView> queue(@AuthenticationPrincipal UserPrincipal u){return service.staffQueue(u.getId());}
 @GetMapping("/{id}") public SupportService.TicketView get(@AuthenticationPrincipal UserPrincipal u,@PathVariable UUID id){return service.staffView(u.getId(),id);}
 @PostMapping("/{id}/replies") public SupportService.TicketView reply(@AuthenticationPrincipal UserPrincipal u,@PathVariable UUID id,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody SupportService.Reply body,HttpServletRequest request){return service.reply(u.getId(),id,key,body,true,context(request,u));}
 @PatchMapping("/{id}") public SupportService.TicketView change(@AuthenticationPrincipal UserPrincipal u,@PathVariable UUID id,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody SupportService.Change body,HttpServletRequest request){return service.change(u.getId(),id,key,body,context(request,u));}
 @PostMapping(value="/{id}/attachments",consumes="multipart/form-data") public SupportController.MediaResponse upload(@AuthenticationPrincipal UserPrincipal u,@PathVariable UUID id,@RequestPart("file")MultipartFile file){ManagedMediaObjectEntity m=service.uploadAttachment(u.getId(),id,file,true);return new SupportController.MediaResponse(m.getId(),m.getPurpose());}
 @GetMapping("/{id}/attachments/{mediaId}") public ResponseEntity<Void> media(@AuthenticationPrincipal UserPrincipal u,@PathVariable UUID id,@PathVariable UUID mediaId){return ResponseEntity.status(302).location(URI.create(service.signedAttachment(u.getId(),id,mediaId,true))).build();}
 private RequestContext context(HttpServletRequest r,UserPrincipal u){return new RequestContext(r.getRemoteAddr(),r.getHeader("User-Agent"),r.getHeader("X-Request-ID"),u.getRole().name());}
}
