package com.utewedding.app.controller;

import com.utewedding.app.domain.ProductReviewReportStatus;
import com.utewedding.app.domain.ProductReviewStatus;
import com.utewedding.app.domain.WeddingStatus;
import com.utewedding.app.repository.AuthSessionRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.ReviewService;
import com.utewedding.app.service.web.CurrentUserContext;
import com.utewedding.app.service.web.WeddingStudioService;
import com.utewedding.app.service.web.FormToken;
import com.utewedding.app.service.impl.SupportService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class StaffOpsWebController {
    private final ReviewService reviews;
    private final WeddingStudioService studio;
    private final CurrentUserContext currentUser;
    private final AuthSessionRepository sessions;
    private final OrderRepository orders;
    private final WeddingRepository weddings;
    private final ProductRepository products;
    private final UserRepository users;
    @org.springframework.beans.factory.annotation.Autowired private SupportService supportService;
    @org.springframework.beans.factory.annotation.Autowired private SupportWebForms supportForms;
    @org.springframework.beans.factory.annotation.Autowired private FormToken formTokens;
    @org.springframework.beans.factory.annotation.Autowired private com.utewedding.app.service.web.WebWeddingService weddingService;

    public StaffOpsWebController(ReviewService reviews, WeddingStudioService studio,
                                 CurrentUserContext currentUser, AuthSessionRepository sessions,
                                 OrderRepository orders, WeddingRepository weddings, ProductRepository products,
                                 UserRepository users) {
        this.reviews = reviews;
        this.studio = studio;
        this.currentUser = currentUser;
        this.sessions = sessions;
        this.orders = orders;
        this.weddings = weddings;
        this.products = products;
        this.users = users;
    }

    @GetMapping("/admin/review-reports")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String reports(@RequestParam(required = false) ProductReviewReportStatus status,
                          @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("reports", reviews.reportViews(status, page, 20));
        model.addAttribute("statuses", ProductReviewReportStatus.values());
        model.addAttribute("status", status == null ? "" : status.name());
        model.addAttribute("status", status == null ? "" : status.name());
        return "admin/review-reports";
    }

    @PostMapping("/admin/review-reports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String resolveReport(@PathVariable UUID id, @RequestParam ProductReviewReportStatus status,
                                Authentication authentication, HttpServletRequest request,
                                RedirectAttributes redirectAttributes) {
        reviews.resolveReport(currentUser.requireUserId(authentication), id, status, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật báo cáo.");
        return "redirect:/admin/review-reports";
    }

    @GetMapping("/admin/weddings")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String weddings(@RequestParam(defaultValue = "") String q,
                           @RequestParam(required = false) WeddingStatus status,
                           @RequestParam(defaultValue = "0") int page,
                           Authentication authentication, HttpServletRequest request, Model model) {
        var weddings = studio.searchForModeration(currentUser.requireUserId(authentication), q, status, page);
        model.addAttribute("weddings", weddings);
        model.addAttribute("weddingOwners", users.findAllById(weddings.getContent().stream()
                .map(wedding -> wedding.getOwnerId()).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(user -> user.getId(),
                        user -> new AccountName(safeAccountName(user.getDisplayName(), user.getEmail()), user.getEmail()))));
        model.addAttribute("q", q);
        model.addAttribute("status", status == null ? "" : status.name());
        model.addAttribute("statuses", WeddingStatus.values());
        java.util.Map<UUID, String> feedbackTokens = new java.util.HashMap<>();
        weddings.getContent().forEach(wedding -> feedbackTokens.put(wedding.getId(),
                formTokens.issue(request, "admin-wedding-feedback:" + wedding.getId())));
        model.addAttribute("feedbackTokens", feedbackTokens);
        return "admin/weddings";
    }

    @GetMapping("/admin/weddings/{id}/preview")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String previewWeddingForStaff(@PathVariable UUID id, Model model) {
        var entity = weddings.findById(id)
                .orElseThrow(() -> new com.utewedding.app.exception.ResourceNotFoundException("Không tìm thấy thiệp cưới."));
        var wedding = weddingService.previewByToken(entity.getPreviewToken());
        model.addAttribute("wedding", wedding);
        model.addAttribute("wishes", wedding.getWishes());
        model.addAttribute("wishForm", new com.utewedding.app.service.web.WebViewModels.WishForm());
        model.addAttribute("rsvpForm", new com.utewedding.app.service.web.WebViewModels.RsvpForm());
        model.addAttribute("adminPreview", true);
        return "wedding/view";
    }

    @PostMapping("/admin/weddings/{id}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String suspend(@PathVariable UUID id, @RequestParam String reason, Authentication authentication,
                          HttpServletRequest request, RedirectAttributes redirectAttributes) {
        studio.suspend(currentUser.requireUserId(authentication), id, reason, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã tạm ngưng thiệp.");
        return "redirect:/admin/weddings";
    }

    @PostMapping("/admin/weddings/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String restore(@PathVariable UUID id, @RequestParam(required = false) String reason,
                          Authentication authentication, HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        studio.restore(currentUser.requireUserId(authentication), id, reason, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã khôi phục thiệp.");
        return "redirect:/admin/weddings";
    }

    @PostMapping("/admin/weddings/{id}/feedback")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String createWeddingFeedback(@PathVariable UUID id, @RequestParam String subject,
                                        @RequestParam String body, @RequestParam String token,
                                        @RequestParam(required = false) java.util.List<MultipartFile> files,
                                        @org.springframework.security.core.annotation.AuthenticationPrincipal
                                        com.utewedding.app.security.UserPrincipal actor,
                                        HttpServletRequest request, RedirectAttributes redirect) {
        try {
            formTokens.verify(request, "admin-wedding-feedback:" + id, token);
            var wedding = weddings.findById(id)
                    .orElseThrow(() -> new com.utewedding.app.exception.ResourceNotFoundException("Không tìm thấy thiệp cưới."));
            var attachments = supportForms.uploads(actor, null, true, token, files, request);
            var ticket = supportService.createByStaff(actor.getId(), wedding.getOwnerId(), id, token,
                    subject, body, attachments, supportForms.context(actor, request));
            formTokens.complete(request, token);
            redirect.addFlashAttribute("successMessage", "Đã gửi góp ý qua ticket " + ticket.number() + ".");
            return "redirect:/admin/support/" + ticket.id();
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
            return "redirect:/admin/weddings";
        }
    }

    private static String safeAccountName(String displayName, String email) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (email != null && !email.isBlank()) return email;
        return "Tài khoản không có tên";
    }

    public record AccountName(String displayName, String email) { }

    @PostMapping("/admin/users/{id}/sessions/{sessionId}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String revokeSession(@PathVariable UUID id, @PathVariable UUID sessionId, RedirectAttributes redirectAttributes) {
        sessions.findById(sessionId).filter(session -> id.equals(session.getUserId())).ifPresent(session -> {
            session.setRevokedAt(Instant.now());
            session.setRevokedReason("ADMIN_REVOKED");
            sessions.save(session);
        });
        redirectAttributes.addFlashAttribute("successMessage", "Đã thu hồi phiên đăng nhập.");
        return "redirect:/admin/users/" + id;
    }

    @GetMapping("/admin/users/{id}/related")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String related(@PathVariable UUID id, Model model) {
        return "redirect:/admin/users/" + id + "#sessions";
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
