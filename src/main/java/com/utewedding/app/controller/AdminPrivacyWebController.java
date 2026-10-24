package com.utewedding.app.controller;

import com.utewedding.app.domain.PrivacyRequestStatus;
import com.utewedding.app.domain.PrivacyRequestType;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.PrivacyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/privacy")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminPrivacyWebController {
    private final PrivacyService privacy;

    public AdminPrivacyWebController(PrivacyService privacy) {
        this.privacy = privacy;
    }

    @GetMapping
    public String list(@RequestParam(required = false) PrivacyRequestType type,
                       @RequestParam(required = false) PrivacyRequestStatus status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        var requests = privacy.listRequests(type, status, PageRequest.of(Math.max(page, 0), 30));
        model.addAttribute("requests", requests);
        model.addAttribute("owners", privacy.ownerViews(requests.getContent().stream()
                .map(row -> row.getUserId()).toList()));
        model.addAttribute("requestTypes", PrivacyRequestType.values());
        model.addAttribute("statuses", PrivacyRequestStatus.values());
        model.addAttribute("selectedType", type == null ? "" : type.name());
        model.addAttribute("selectedStatus", status == null ? "" : status.name());
        return "admin/privacy";
    }

    @PostMapping("/{id}/decision")
    public String decision(@PathVariable UUID id, @RequestParam boolean approve,
                           @RequestParam(defaultValue = "false") boolean confirmed,
                           @RequestParam(required = false) String reason,
                           @AuthenticationPrincipal UserPrincipal actor,
                           HttpServletRequest request, RedirectAttributes redirect) {
        if (approve && !confirmed) {
            redirect.addFlashAttribute("errorMessage", "Cần xác nhận rõ ràng trước khi duyệt yêu cầu xóa tài khoản.");
            return "redirect:/admin/privacy";
        }
        privacy.processDeletion(id, actor.getId(), approve, reason, context(request));
        redirect.addFlashAttribute("successMessage", approve
                ? "Yêu cầu đã được xử lý và tài khoản đã được ẩn danh."
                : "Yêu cầu đã bị từ chối.");
        return "redirect:/admin/privacy";
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
