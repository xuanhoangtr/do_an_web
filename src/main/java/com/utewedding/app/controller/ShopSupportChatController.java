package com.utewedding.app.controller;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.CloudinaryPrivateMediaProxy;
import com.utewedding.app.service.impl.ShopSupportChatService;
import com.utewedding.app.service.impl.SupportChatService.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping({"/support/shops/{shopId}/chat", "/vendor/chat/api"})
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class ShopSupportChatController {
    private final ShopSupportChatService chat;
    private final com.utewedding.app.service.impl.SupportChatRecipientResolver recipients;
    private final org.springframework.context.ApplicationEventPublisher events;
    public ShopSupportChatController(ShopSupportChatService chat,
            com.utewedding.app.service.impl.SupportChatRecipientResolver recipients,
            org.springframework.context.ApplicationEventPublisher events) {
        this.chat = chat; this.recipients = recipients; this.events = events;
    }
    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(value);
    }
    @GetMapping("/conversations")
    public ResponseEntity<?> list(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @RequestParam(defaultValue="0") int page) {
        var rows = shopId == null ? chat.vendorConversations(user, page) : chat.customerConversations(user, shopId, page);
        return response(new SupportChatController.PageResponse<>(rows.getContent(), rows.getNumber(), rows.getSize(), rows.hasNext(), rows.getTotalElements()));
    }
    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @PathVariable UUID id) {
        return response(shopId == null ? chat.vendorConversation(user, id) : chat.customerConversation(user, shopId, id));
    }
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<?> messages(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @PathVariable UUID id, @RequestParam(defaultValue="0") int page) {
        var result = shopId == null ? chat.vendorMessages(user, id, page) : chat.customerMessages(user, shopId, id, page);
        String root = shopId == null ? "/vendor/chat/api" : "/support/shops/" + shopId + "/chat";
        return response(new MessagePage(result.messages().stream().map(message -> new ChatMessage(
                message.id(), message.authorId(), message.authorName(), message.authorRole(), message.visibility(),
                message.body(), message.attachments().stream().map(item -> new ChatAttachment(item.id(),
                        root + "/conversations/" + id + "/attachments/" + item.id())).toList(),
                message.createdAt(), user.getId().equals(message.authorId()))).toList(), result.page(), result.hasMore(), result.total()));
    }
    @PostMapping("/conversations")
    public ResponseEntity<?> create(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody ShopSupportChatService.CreateConversation command) {
        if (shopId == null) throw new org.springframework.security.access.AccessDeniedException("Khách hàng bắt đầu hội thoại.");
        return response(chat.createCustomerConversation(user, shopId, key, command));
    }
    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> reply(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody ShopSupportChatService.Reply command) {
        return response(shopId == null ? chat.vendorReply(user, id, key, command) : chat.customerReply(user, shopId, id, key, command));
    }
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<?> read(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @PathVariable UUID id) {
        if (shopId == null) chat.markVendorRead(user, id); else chat.markCustomerRead(user, shopId, id);
        var ticket = recipients.ticket(id);
        events.publishEvent(new SupportChatEvent(id, "READ_CHANGED", user.getId(), null, false, recipients.recipients(ticket)));
        return response(null);
    }
    @PostMapping("/typing")
    public ResponseEntity<?> typing(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @RequestBody SupportChatController.TypingRequest body) {
        if (shopId == null) chat.vendorTyping(user, body.conversationId(), body.active());
        else chat.customerTyping(user, shopId, body.conversationId(), body.active());
        return response(null);
    }
    @GetMapping("/conversations/{id}/typing")
    public ResponseEntity<?> typingUsers(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @PathVariable UUID id) {
        return response(shopId == null ? chat.activeVendorTyping(user, id) : chat.activeCustomerTyping(user, shopId, id));
    }
    @GetMapping("/unread-count")
    public ResponseEntity<?> unread(@AuthenticationPrincipal UserPrincipal user, @PathVariable(required=false) UUID shopId) {
        return response(new SupportChatController.UnreadCount(shopId == null ? chat.vendorUnreadCount(user) : chat.customerUnreadCount(user, shopId)));
    }
    @PostMapping(value="/attachments", consumes="multipart/form-data")
    public ResponseEntity<?> upload(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @RequestPart("files") List<MultipartFile> files) {
        return response(shopId == null ? chat.uploadVendorImages(user, files) : chat.uploadCustomerImages(user, shopId, files));
    }
    @GetMapping("/conversations/{id}/attachments/{mediaId}")
    public ResponseEntity<StreamingResponseBody> attachment(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable(required=false) UUID shopId, @PathVariable UUID id, @PathVariable UUID mediaId) {
        ShopSupportChatService.PrivateAttachmentDownload download = shopId == null
                ? chat.vendorAttachment(user, id, mediaId) : chat.customerAttachment(user, shopId, id, mediaId);
        CloudinaryPrivateMediaProxy.OpenedDownload stream = download.stream();
        StreamingResponseBody body = output -> {
            try (stream) {
                byte[] buffer = new byte[8192];
                long written = 0;
                int count;
                while ((count = stream.inputStream().read(buffer)) != -1) {
                    written += count;
                    if (written > stream.maxBytes()) throw new IOException("Media exceeds the configured limit.");
                    output.write(buffer, 0, count);
                }
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(download.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(stream.contentType()));
        if (stream.contentLength() >= 0) response.contentLength(stream.contentLength());
        return response.body(body);
    }
}
