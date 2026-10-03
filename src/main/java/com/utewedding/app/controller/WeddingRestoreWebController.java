package com.utewedding.app.controller;

import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.web.CurrentUserContext;
import com.utewedding.app.service.web.WeddingStudioService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WeddingRestoreWebController {
    private final CurrentUserContext currentUser;
    private final WeddingStudioService studio;

    public WeddingRestoreWebController(CurrentUserContext currentUser, WeddingStudioService studio) {
        this.currentUser = currentUser;
        this.studio = studio;
    }

    @PostMapping("/wedding/{id}/restore")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String reopen(@PathVariable UUID id, Authentication authentication, HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        try {
            UUID ownerId = currentUser.requireUserId(authentication);
            RequestContext context = new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                    request.getHeader("X-Request-Id"));
            studio.reopenArchived(ownerId, id, context);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã mở lại thiệp ở chế độ riêng tư. Bạn có thể xem trước hoặc xuất bản lại khi sẵn sàng.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/wedding";
    }
}
