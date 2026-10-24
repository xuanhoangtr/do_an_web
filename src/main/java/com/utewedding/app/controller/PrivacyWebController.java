package com.utewedding.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.PrivacyService;
import com.utewedding.app.service.web.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PrivacyWebController {
    private final PrivacyService privacy;
    private final CurrentUserContext currentUser;
    private final ObjectMapper objectMapper;

    public PrivacyWebController(PrivacyService privacy, CurrentUserContext currentUser, ObjectMapper objectMapper) {
        this.privacy = privacy;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/privacy")
    public String notice(Model model) {
        model.addAttribute("noticeVersion", PrivacyService.NOTICE_VERSION);
        model.addAttribute("inventory", privacy.dataInventory());
        model.addAttribute("retentionRules", privacy.retentionRules());
        return "privacy/notice";
    }

    @GetMapping("/profile/privacy")
    @PreAuthorize("isAuthenticated()")
    public String dashboard(Authentication authentication, Model model) {
        UUID userId = currentUser.requireUserId(authentication);
        model.addAttribute("marketingConsent", privacy.marketingConsent(userId));
        var requests = privacy.requestsForUser(userId);
        model.addAttribute("privacyRequests", requests);
        model.addAttribute("deletionPending", requests.stream().anyMatch(row ->
                row.getRequestType() == com.utewedding.app.domain.PrivacyRequestType.DELETION
                        && (row.getStatus() == com.utewedding.app.domain.PrivacyRequestStatus.PENDING
                        || row.getStatus() == com.utewedding.app.domain.PrivacyRequestStatus.IN_PROGRESS)));
        model.addAttribute("noticeVersion", PrivacyService.NOTICE_VERSION);
        return "profile/privacy";
    }

    @PostMapping("/profile/privacy/consent")
    @PreAuthorize("isAuthenticated()")
    public String consent(@RequestParam(defaultValue = "false") boolean granted,
                          Authentication authentication, HttpServletRequest request,
                          RedirectAttributes redirect) {
        privacy.recordMarketingConsent(currentUser.requireUserId(authentication), granted, context(request));
        redirect.addFlashAttribute("successMessage", "Lựa chọn quyền riêng tư đã được ghi nhận.");
        return "redirect:/profile/privacy";
    }

    @PostMapping("/profile/privacy/export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> export(Authentication authentication, HttpServletRequest request)
            throws JsonProcessingException {
        UUID userId = currentUser.requireUserId(authentication);
        byte[] payload = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(privacy.exportData(userId, context(request)))
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=personal-data-" + LocalDate.now() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    @PostMapping("/profile/privacy/deletion")
    @PreAuthorize("isAuthenticated()")
    public String deletion(@RequestParam String currentPassword,
                           @RequestParam(required = false) String reason,
                           Authentication authentication, HttpServletRequest request,
                           RedirectAttributes redirect) {
        try {
            privacy.requestDeletion(currentUser.requireUserId(authentication), currentPassword,
                    reason, context(request));
            redirect.addFlashAttribute("successMessage", "Yêu cầu xóa tài khoản đã được gửi và đang chờ quản trị viên duyệt.");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/profile/privacy";
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
