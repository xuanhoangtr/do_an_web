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

@RestController
@RequestMapping("/api/v1/support/tickets")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class SupportController {
    private final SupportService service;
    public SupportController(SupportService service) { this.service = service; }
    @PostMapping public SupportService.TicketView create(@AuthenticationPrincipal UserPrincipal user,
            @RequestHeader(name="Idempotency-Key",required=false) String key,
            @RequestBody SupportService.CreateTicket body,HttpServletRequest request){return service.create(user.getId(),key,body,context(request));}
    @GetMapping public List<SupportService.TicketView> list(@AuthenticationPrincipal UserPrincipal user){return service.customerList(user.getId());}
    @GetMapping("/{id}") public SupportService.TicketView get(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id){return service.customerView(user.getId(),id);}
    @PostMapping("/{id}/replies") public SupportService.TicketView reply(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody SupportService.Reply body,HttpServletRequest request){return service.reply(user.getId(),id,key,new SupportService.Reply(body.body(),false,body.attachmentMediaIds()),false,context(request));}
    @PostMapping(value="/{id}/attachments",consumes="multipart/form-data") public MediaResponse upload(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@RequestPart("file")MultipartFile file){ManagedMediaObjectEntity media=service.uploadAttachment(user.getId(),id,file,false);return new MediaResponse(media.getId(),media.getPurpose());}
    @GetMapping("/{id}/attachments/{mediaId}") public ResponseEntity<Void> media(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@PathVariable UUID mediaId){return ResponseEntity.status(302).location(URI.create(service.signedAttachment(user.getId(),id,mediaId,false))).build();}
    private RequestContext context(HttpServletRequest r){return new RequestContext(r.getRemoteAddr(),r.getHeader("User-Agent"),r.getHeader("X-Request-ID"),"CUSTOMER");}
    public record MediaResponse(UUID id,String purpose){}
}
