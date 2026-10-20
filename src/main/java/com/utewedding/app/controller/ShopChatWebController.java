package com.utewedding.app.controller;

import com.utewedding.app.domain.*;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ShopSupportChatService;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class ShopChatWebController {
    private final ShopSupportChatService chat;
    private final ShopRepository shops;
    public ShopChatWebController(ShopSupportChatService chat, ShopRepository shops) { this.chat = chat; this.shops = shops; }
    @GetMapping("/support/shops/by-slug/{slug}")
    public String fromProduct(@AuthenticationPrincipal UserPrincipal user, @PathVariable String slug,
                              @RequestParam(defaultValue = "false") boolean newConversation,
                              HttpServletResponse response) {
        requireCustomerRoute(user);
        noStore(response);
        var shop = shops.findBySlugIgnoreCaseAndKindAndStatus(slug, ShopKind.VENDOR, ShopStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        chat.customerConversations(user, shop.getId(), 0);
        return "redirect:/support/shops/" + shop.getId() + (newConversation ? "?newConversation=true" : "");
    }
    /** Compatibility overload for callers that render the controller without a servlet response. */
    public String fromProduct(UserPrincipal user, String slug, boolean newConversation) {
        return fromProduct(user, slug, newConversation, null);
    }
    @GetMapping("/support/shops/{shopId}")
    public String customer(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID shopId,
            @RequestParam(required=false) UUID conversationId,
            @RequestParam(defaultValue = "false") boolean newConversation, Model model,
            HttpServletResponse response) {
        requireCustomerRoute(user);
        noStore(response);
        chat.customerConversations(user, shopId, 0);
        if (conversationId != null) chat.customerConversation(user, shopId, conversationId);
        var shop = shops.findById(shopId)
                .filter(item -> item.getKind() == ShopKind.VENDOR)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("shopChatApi", "/support/shops/" + shopId + "/chat");
        model.addAttribute("shopChatVendor", false);
        model.addAttribute("shopChatSelected", conversationId);
        model.addAttribute("supportChatConversationId", conversationId);
        model.addAttribute("supportChatAutoOpen", conversationId != null);
        model.addAttribute("shopChatShopId", shopId);
        model.addAttribute("shopChatNew", newConversation);
        model.addAttribute("shopChatName", shop.getDisplayName());
        return "operations/shop-chat-inbox";
    }
    /** Compatibility overload for callers that render the controller without a servlet response. */
    public String customer(UserPrincipal user, UUID shopId, UUID conversationId,
            boolean newConversation, Model model) {
        return customer(user, shopId, conversationId, newConversation, model, null);
    }

    private static void requireCustomerRoute(UserPrincipal user) {
        if (user == null || user.isVendorAccount()) {
            throw new AccessDeniedException("Khu vực này chỉ dành cho khách hàng.");
        }
    }

    private static void noStore(HttpServletResponse response) {
        if (response == null) return;
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}
