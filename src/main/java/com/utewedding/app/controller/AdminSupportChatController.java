package com.utewedding.app.controller;

import com.utewedding.app.domain.SupportTicketStatus;
import com.utewedding.app.exception.SupportChatParticipationRequiredException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.SupportChatService;
import com.utewedding.app.service.impl.SupportChatService.*;
import com.utewedding.app.service.impl.SupportService;
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
@RequestMapping("/admin/support/chat")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class AdminSupportChatController {
    private final SupportChatService chat;
    public AdminSupportChatController(SupportChatService chat) { this.chat = chat; }

    @GetMapping("/conversations")
    public ResponseEntity<PageResponse<ConversationSummary>> conversations(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String q, @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(defaultValue = "true") boolean needsReply) {
        return privateResponse(page(chat.staffConversations(user.getId(), page, q, status, mine, unassigned, needsReply)));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationDetail> conversation(@AuthenticationPrincipal UserPrincipal user,
                                                            @PathVariable UUID id) {
        return privateResponse(chat.staffConversation(user.getId(), id));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<MessagePage> messages(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
        return privateResponse(chat.staffMessages(user.getId(), id, page));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> reply(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody StaffMessage body,
            HttpServletRequest request) {
        return privateResponse(chat.staffSend(user.getId(), id, key, body, context(request, user)));
    }

    @PostMapping("/conversations/{id}/claim")
    public ResponseEntity<ConversationDetail> claim(@AuthenticationPrincipal UserPrincipal user,
                                                     @PathVariable UUID id) {
        return privateResponse(chat.claim(user.getId(), id));
    }

    @PostMapping("/conversations/{id}/join")
    public ResponseEntity<ConversationDetail> join(@AuthenticationPrincipal UserPrincipal user,
                                                    @PathVariable UUID id) {
        return privateResponse(chat.join(user.getId(), id));
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        chat.markStaffRead(user.getId(), id);
        return noContent();
    }

    @PostMapping("/conversations/{id}/status")
    public ResponseEntity<ConversationDetail> status(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestBody StatusRequest body, HttpServletRequest request) {
        return privateResponse(chat.staffChange(user.getId(), id, key, body.status(), context(request, user)));
    }

    @PostMapping("/typing")
    public ResponseEntity<Void> typing(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody TypingRequest body) {
        chat.requireTypingAccess(user.getId(), body.conversationId(), true);
        chat.publishTyping(body.conversationId(), user.getId(), user.getDisplayName(), body.active(),
                true, body.internal(), chat.customerIdForTyping(user.getId(), body.conversationId()));
        return noContent();
    }

    @GetMapping("/conversations/{id}/typing")
    public ResponseEntity<List<TypingUser>> activeTyping(@AuthenticationPrincipal UserPrincipal user,
                                                         @PathVariable UUID id) {
        return privateResponse(chat.activeTyping(user.getId(), id, true));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCount> unread(@AuthenticationPrincipal UserPrincipal user) {
        return privateResponse(new UnreadCount(chat.staffUnreadCount(user.getId())));
    }

    @PostMapping(value = "/attachments", consumes = "multipart/form-data")
    public ResponseEntity<List<UploadedImage>> upload(@AuthenticationPrincipal UserPrincipal user,
            @RequestPart("files") List<MultipartFile> files) {
        return privateResponse(chat.uploadImages(user.getId(), files, true));
    }

    @GetMapping("/conversations/{id}/attachments/{mediaId}")
    public ResponseEntity<Void> attachment(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id, @PathVariable UUID mediaId) {
        return ResponseEntity.status(302).location(URI.create(chat.staffAttachment(user.getId(), id, mediaId)))
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
    public ResponseEntity<SupportChatController.ParticipationRequired> participationRequired(
            SupportChatParticipationRequiredException error) {
        return ResponseEntity.status(409).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new SupportChatController.ParticipationRequired("STAFF_PARTICIPATION_REQUIRED", error.getAssignedAgentName()));
    }

    public record PageResponse<T>(List<T> items, int page, int pageSize, boolean hasMore, long total) { }
    public record UnreadCount(long count) { }
    public record TypingRequest(UUID conversationId, boolean active, boolean internal) { }
    public record StatusRequest(SupportTicketStatus status) { }
}
