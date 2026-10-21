package com.utewedding.app.controller;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.CustomerMessagesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Customer-only entry points for the mixed messages inbox. */
@RestController
@RequestMapping("/messages")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class CustomerMessagesController {
    private final CustomerMessagesService messages;

    public CustomerMessagesController(CustomerMessagesService messages) {
        this.messages = messages;
    }

    @PostMapping("/shop-contacts/open")
    public ResponseEntity<CustomerMessagesService.OpenContactResponse> openShopContact(
            @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody OpenShopContactRequest request) {
        return privateResponse(messages.openShopContact(user, request.shopId(), request.productId()));
    }

    @PostMapping(value = "/shop-contacts/open", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> openShopContactForm(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam UUID shopId, @RequestParam(required = false) UUID productId) {
        CustomerMessagesService.OpenContactResponse contact = messages.openShopContact(user, shopId, productId);
        String target = ServletUriComponentsBuilder.fromPath("/support/shops/{shopId}")
                .queryParam("contactId", "{contactId}")
                .buildAndExpand(shopId, contact.contactId()).encode().toUriString();
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }

    @GetMapping("/inbox")
    public ResponseEntity<CustomerMessagesService.InboxPage> inbox(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "ALL") CustomerMessagesService.InboxScope scope,
            @RequestParam(defaultValue = "0") int page) {
        return privateResponse(messages.inbox(user, scope, page));
    }

    @GetMapping("/shop-contacts/{contactId}")
    public ResponseEntity<CustomerMessagesService.InboxItem> contact(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable UUID contactId) {
        return privateResponse(messages.contact(user, contactId));
    }

    @GetMapping("/unread")
    public ResponseEntity<CustomerMessagesService.UnreadSummary> unread(
            @AuthenticationPrincipal UserPrincipal user) {
        return privateResponse(messages.unread(user));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    public record OpenShopContactRequest(@NotNull UUID shopId, UUID productId) { }
}
