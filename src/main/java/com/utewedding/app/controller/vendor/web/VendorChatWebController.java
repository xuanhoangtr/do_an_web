package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ShopSupportChatService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Controller
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorChatWebController {
    private final ShopSupportChatService chat;
    public VendorChatWebController(ShopSupportChatService chat) { this.chat = chat; }
    @GetMapping("/vendor/chat")
    public String inbox(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required=false) UUID conversationId, HttpServletResponse response, Model model) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        chat.vendorConversations(user, 0);
        if (conversationId != null) chat.vendorConversation(user, conversationId);
        model.addAttribute("shopChatApi", "/vendor/chat/api");
        model.addAttribute("shopChatVendor", true);
        model.addAttribute("shopChatSelected", conversationId);
        model.addAttribute("shopChatName", "Hộp thư cửa hàng");
        return "operations/shop-chat-inbox";
    }
}
