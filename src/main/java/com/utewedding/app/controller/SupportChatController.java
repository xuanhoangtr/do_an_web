package com.utewedding.app.controller;

import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.exception.SupportChatParticipationRequiredException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.SupportChatService;
import com.utewedding.app.service.impl.SupportChatService.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/support/chat")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class SupportChatController {
    private final SupportChatService chat;
    public SupportChatController(SupportChatService chat) { this.chat = chat; }

    @GetMapping("/conversations")
    public ResponseEntity<PageResponse<ConversationSummary>> conversations(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam(defaultValue = "0") int page) {
        return privateResponse(page(chat.customerConversations(user.getId(), page)));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationDetail> conversation(@AuthenticationPrincipal UserPrincipal user,
                                                            @PathVariable UUID id) {
        return privateResponse(chat.customerConversation(user.getId(), id));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<MessagePage> messages(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
        return privateResponse(chat.customerMessages(user.getId(), id, page));
    }

    @PostMapping("/conversations")
    public ResponseEntity<?> create(@AuthenticationPrincipal UserPrincipal user,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CustomerMessage body,
            HttpServletRequest request) {
        return privateResponse(chat.customerSend(user.getId(), null, key, body, context(request, user)));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> reply(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CustomerMessage body,
            HttpServletRequest request) {
        return privateResponse(chat.customerSend(user.getId(), id, key, body, context(request, user)));
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        chat.markCustomerRead(user.getId(), id);
        return noContent();
    }

    @PostMapping("/typing")
    public ResponseEntity<Void> typing(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody TypingRequest body) {
        chat.requireTypingAccess(user.getId(), body.conversationId(), false);
        chat.publishTyping(body.conversationId(), user.getId(), user.getDisplayName(), body.active(),
                false, false, user.getId());
        return noContent();
    }

    @GetMapping("/conversations/{id}/typing")
    public ResponseEntity<List<TypingUser>> activeTyping(@AuthenticationPrincipal UserPrincipal user,
                                                         @PathVariable UUID id) {
        return privateResponse(chat.activeTyping(user.getId(), id, false));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCount> unread(@AuthenticationPrincipal UserPrincipal user) {
        return privateResponse(new UnreadCount(chat.customerUnreadCount(user.getId())));
    }

    @PostMapping(value = "/attachments", consumes = "multipart/form-data")
    public ResponseEntity<List<UploadedImage>> upload(@AuthenticationPrincipal UserPrincipal user,
            @RequestPart("files") List<MultipartFile> files) {
        return privateResponse(chat.uploadImages(user.getId(), files, false));
    }

    @GetMapping("/conversations/{id}/attachments/{mediaId}")
    public ResponseEntity<Void> attachment(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id, @PathVariable UUID mediaId) {
        return ResponseEntity.status(302).location(URI.create(chat.customerAttachment(user.getId(), id, mediaId)))
                .cacheControl(CacheControl.noStore()).build();
    }

    private static RequestContext context(HttpServletRequest request, UserPrincipal user) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-ID"), user.getRole().name());
    }
    private static <T> PageResponse<T> page(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.hasNext(), page.getTotalElements());
    }
    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }
    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }

    @ExceptionHandler(SupportChatParticipationRequiredException.class)
    public ResponseEntity<ParticipationRequired> participationRequired(SupportChatParticipationRequiredException error) {
        return ResponseEntity.status(409).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ParticipationRequired("STAFF_PARTICIPATION_REQUIRED", error.getAssignedAgentName()));
    }

    public record PageResponse<T>(List<T> items, int page, int pageSize, boolean hasMore, long total) { }
    public record UnreadCount(long count) { }
    public record TypingRequest(UUID conversationId, boolean active) { }
    public record ParticipationRequired(String code, String assignedAgentName) { }
}
