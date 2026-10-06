package com.utewedding.app.controller;

import com.utewedding.app.domain.WishStatus;
import com.utewedding.app.exception.WeddingPublishValidationException;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.WeddingService;
import com.utewedding.app.service.web.CurrentUserContext;
import com.utewedding.app.service.web.WeddingStudioService;
import com.utewedding.app.service.web.WebViewModels;
import com.utewedding.app.service.web.WebWeddingService;
import com.utewedding.app.service.MediaService;
import com.utewedding.app.dto.request.MediaUploadRequest;
import com.utewedding.app.domain.MediaAssetKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.validation.BindingResult;
import org.springframework.security.access.prepost.PreAuthorize;

@Controller
public class WeddingWebController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WeddingWebController.class);

    private final WebWeddingService weddingService;
    private final CurrentUserContext currentUser;
    private final MediaService mediaService;
    private final WeddingStudioService studio;
    private final WeddingService weddingApi;

    /** Compatibility constructor for controller tests that do not upload media. */
    public WeddingWebController(WebWeddingService weddingService, CurrentUserContext currentUser) {
        this(weddingService, currentUser, null, null, null);
    }

    public WeddingWebController(WebWeddingService weddingService, CurrentUserContext currentUser,
                                MediaService mediaService) {
        this(weddingService, currentUser, mediaService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WeddingWebController(WebWeddingService weddingService,
                                CurrentUserContext currentUser,
                                MediaService mediaService,
                                WeddingStudioService studio,
                                WeddingService weddingApi) {
        this.weddingService = weddingService;
        this.currentUser = currentUser;
        this.mediaService = mediaService;
        this.studio = studio;
        this.weddingApi = weddingApi;
    }

    @GetMapping("/wedding")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String weddings(Authentication authentication, Model model) {
        UUID userId = currentUser.requireUserId(authentication);
        model.addAttribute("templates", weddingService.templates());
        model.addAttribute("myWeddings", weddingService.myWeddings(userId));
        return "wedding/index";
    }

    @GetMapping({"/wedding/create", "/wedding/editor"})
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String create(Authentication authentication, Model model) {
        if (!model.containsAttribute("weddingForm")) {
            model.addAttribute("weddingForm", weddingService.form(
                    currentUser.requireUserId(authentication), null));
        }
        model.addAttribute("templates", weddingService.templates());
        model.addAttribute("activeEditorStep", normalizeEditorStep(null));
        return "wedding/editor";
    }

    @PostMapping({"/wedding/save", "/wedding/editor"})
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String save(Authentication authentication,
                       @Valid @ModelAttribute("weddingForm") WebViewModels.WeddingForm form,
                       BindingResult bindingResult,
                       Model model,
                       @RequestParam(value = "coverImage", required = false) MultipartFile coverImage,
                       @RequestParam(value = "editorStep", required = false) String editorStep,
                       RedirectAttributes redirectAttributes) {
        model.addAttribute("activeEditorStep", editorStepForErrors(bindingResult, editorStep));
        if (bindingResult.hasErrors()) {
            model.addAttribute("templates", weddingService.templates());
            return "wedding/editor";
        }
        WebViewModels.WeddingForm saved = null;
        try {
            UUID ownerId = currentUser.requireUserId(authentication);
            saved = weddingService.save(ownerId, form);
            if (coverImage != null && !coverImage.isEmpty()) {
                if (mediaService == null) {
                    throw new IllegalStateException("Media service chưa được cấu hình.");
                }
                mediaService.upload(ownerId, new MediaUploadRequest(
                        coverImage, saved.getId(), MediaAssetKind.COVER, "Ảnh bìa thiệp", null));
            }
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu nội dung thiệp cưới.");
            return "redirect:/wedding/my-cards";
        } catch (RuntimeException exception) {
            log.warn("Wedding editor save failed (media stage: {})", saved != null, exception);
            if (saved != null) {
                redirectAttributes.addFlashAttribute("errorMessage", "Nội dung thiệp đã được lưu, nhưng ảnh chưa tải lên được. Vui lòng chọn lại ảnh và thử lại.");
                return "redirect:/wedding/" + saved.getId() + "/edit?step=media";
            }
            bindingResult.reject("wedding.save", WebErrorMessages.forException(exception));
            model.addAttribute("activeEditorStep", normalizeEditorStep(editorStep));
            model.addAttribute("templates", weddingService.templates());
            return "wedding/editor";
        }
    }

    @GetMapping("/wedding/my-cards")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String myCards(Authentication authentication, Model model) {
        return weddings(authentication, model);
    }

    @GetMapping("/wedding/{id}/edit")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String edit(@PathVariable UUID id, Authentication authentication, Model model,
                       @RequestParam(value = "step", required = false) String step) {
        model.addAttribute("weddingForm", weddingService.form(currentUser.requireUserId(authentication), id));
        model.addAttribute("templates", weddingService.templates());
        model.addAttribute("activeEditorStep", normalizeEditorStep(step));
        return "wedding/editor";
    }

    @PostMapping("/wedding/{id}/publish")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String publish(@PathVariable UUID id, Authentication authentication, HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        return lifecycle(id, authentication, request, redirectAttributes, "publish");
    }

    @PostMapping("/wedding/{id}/unpublish")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String unpublish(@PathVariable UUID id, Authentication authentication, HttpServletRequest request,
                            RedirectAttributes redirectAttributes) {
        return lifecycle(id, authentication, request, redirectAttributes, "unpublish");
    }

    @PostMapping("/wedding/{id}/archive")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String archive(@PathVariable UUID id, Authentication authentication, HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        return lifecycle(id, authentication, request, redirectAttributes, "archive");
    }

    @GetMapping("/preview/{token}")
    public String preview(@PathVariable String token, Model model) {
        var wedding = weddingService.previewByToken(token);
        model.addAttribute("wedding", wedding);
        model.addAttribute("wishes", wedding.getWishes());
        model.addAttribute("wishForm", new WebViewModels.WishForm());
        model.addAttribute("rsvpForm", new WebViewModels.RsvpForm());
        return "wedding/view";
    }

    @GetMapping("/w/{slug}/k/{token}")
    public String guestLink(@PathVariable String slug, @PathVariable String token, Model model) {
        return publicView(slug, token, model);
    }

    @GetMapping({"/w/{slug}", "/wedding/view/{slug}"})
    public String publicView(@PathVariable String slug,
                             @RequestParam(required = false, name = "token") String token,
                             Model model) {
        WebViewModels.PublicWeddingView wedding = weddingService.publicWedding(slug, token);
        model.addAttribute("wedding", wedding);
        model.addAttribute("wishes", wedding.getWishes());
        if (!model.containsAttribute("wishForm")) {
            model.addAttribute("wishForm", new WebViewModels.WishForm());
        }
        if (!model.containsAttribute("rsvpForm")) {
            WebViewModels.RsvpForm rsvp = new WebViewModels.RsvpForm();
            rsvp.setToken(token);
            if (wedding.getRsvpStatus() != null) {
                rsvp.setAttendanceStatus(wedding.getRsvpStatus());
                rsvp.setAttendeeCount(Math.max(1, wedding.getRsvpAttendees()));
                rsvp.setNote(wedding.getRsvpNote());
            }
            model.addAttribute("rsvpForm", rsvp);
        }
        return "wedding/view";
    }

    @PostMapping("/w/{slug}/rsvp")
    public String rsvp(@PathVariable String slug,
                       @ModelAttribute("rsvpForm") WebViewModels.RsvpForm form,
                       RedirectAttributes redirectAttributes) {
        try {
            weddingService.submitRsvp(slug, form);
        } catch (com.utewedding.app.exception.BusinessConflictException exception) {
            redirectAttributes.addFlashAttribute("rsvpError", WebErrorMessages.forException(exception));
            redirectAttributes.addFlashAttribute("rsvpForm", form);
            return "redirect:/w/" + slug + "#rsvp";
        } catch (com.utewedding.app.exception.ResourceNotFoundException exception) {
            redirectAttributes.addFlashAttribute("rsvpError", "Liên kết khách mời không còn hợp lệ. Bạn có thể nhập họ tên để gửi phản hồi từ trang thiệp.");
            form.setToken(null);
            redirectAttributes.addFlashAttribute("rsvpForm", form);
            return "redirect:/w/" + slug + "#rsvp";
        }
        redirectAttributes.addFlashAttribute("successMessage", "Đã ghi nhận phản hồi của bạn.");
        if (form.getToken() != null && !form.getToken().isBlank()) {
            return "redirect:/w/" + slug + "/k/" + form.getToken() + "?rsvp=success";
        }
        return "redirect:/w/" + slug + "?rsvp=success";
    }

    @GetMapping("/w/{slug}/rsvp")
    public String rsvpPage(@PathVariable String slug) {
        return "redirect:/w/" + slug + "#rsvp";
    }

    @PostMapping({"/w/{slug}/wish", "/wedding/view/{slug}/wish"})
    public String wish(@PathVariable String slug,
                       @Valid @ModelAttribute("wishForm") WebViewModels.WishForm form,
                       BindingResult bindingResult, Model model,
                       RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) return publicView(slug, null, model);
        String sender = form.getSenderName() == null || form.getSenderName().isBlank()
                ? "Một người thương mến" : form.getSenderName().trim();
        try {
            weddingService.submitWish(slug, sender, form.getMessage());
        } catch (RuntimeException exception) {
            log.warn("Public wedding wish submission failed", exception);
            bindingResult.reject("wish.submit", WebErrorMessages.forException(exception));
            return publicView(slug, null, model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "Lời chúc đã được gửi. Cảm ơn bạn!");
        return "redirect:/w/" + slug + "?wish=sent";
    }

    @GetMapping("/wedding/{id}/guests")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String managedGuests(Authentication authentication, @PathVariable UUID id, Model model) {
        UUID ownerId = currentUser.requireUserId(authentication);
        model.addAttribute("wedding", weddingService.form(ownerId, id));
        var guestRows = studio.guestRows(ownerId, id);
        long attending = guestRows.stream().filter(row -> "ATTENDING".equals(row.rsvpStatus())).count();
        long declined = guestRows.stream().filter(row -> "NOT_ATTENDING".equals(row.rsvpStatus())).count();
        int attendees = guestRows.stream().filter(row -> "ATTENDING".equals(row.rsvpStatus()))
                .mapToInt(row -> row.attendeeCount() == null ? 0 : row.attendeeCount()).sum();
        model.addAttribute("guestRows", guestRows);
        model.addAttribute("guestCount", guestRows.size());
        model.addAttribute("attendingCount", attending);
        model.addAttribute("attendeeCount", attendees);
        model.addAttribute("declinedCount", declined);
        model.addAttribute("pendingCount", Math.max(0, guestRows.size() - attending - declined));
        return "wedding/guests";
    }

    @PostMapping("/wedding/{id}/guests")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String addGuest(@PathVariable UUID id, @RequestParam String name,
                           @RequestParam(required = false) String relationship,
                           @RequestParam(required = false) Integer maxAttendees,
                           @RequestParam(required = false) String invitation,
                           Authentication authentication, HttpServletRequest request,
                           RedirectAttributes redirectAttributes) {
        studio.addGuest(currentUser.requireUserId(authentication), id, name, relationship, maxAttendees,
                invitation, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã thêm khách mời.");
        return "redirect:/wedding/" + id + "/guests";
    }

    @PostMapping("/wedding/{id}/guests/{guestId}/token")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String regenerate(@PathVariable UUID id, @PathVariable UUID guestId, Authentication authentication,
                             HttpServletRequest request, RedirectAttributes redirectAttributes) {
        studio.regenerateToken(currentUser.requireUserId(authentication), id, guestId, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã tạo lại link khách mời.");
        return "redirect:/wedding/" + id + "/guests";
    }

    @PostMapping("/wedding/{id}/guests/{guestId}/delete")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String deleteGuest(@PathVariable UUID id, @PathVariable UUID guestId, Authentication authentication,
                              HttpServletRequest request, RedirectAttributes redirectAttributes) {
        studio.deleteGuest(currentUser.requireUserId(authentication), id, guestId, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã xóa khách mời.");
        return "redirect:/wedding/" + id + "/guests";
    }

    @GetMapping(value = "/wedding/{id}/guests/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    @org.springframework.web.bind.annotation.ResponseBody
    public String exportGuests(@PathVariable UUID id, Authentication authentication) {
        return studio.exportCsv(currentUser.requireUserId(authentication), id);
    }

    @PostMapping("/wedding/{id}/guests/import")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String importGuests(@PathVariable UUID id, @RequestParam String csv, Authentication authentication,
                               HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            int count = studio.importCsv(currentUser.requireUserId(authentication), id, csv, context(request));
            redirectAttributes.addFlashAttribute("successMessage", "Đã nhập " + count + " khách mời.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/wedding/" + id + "/guests";
    }

    @GetMapping("/wedding/{id}/wishes")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String wishes(@PathVariable UUID id, Authentication authentication,
                         @RequestParam(required = false) WishStatus status,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        UUID ownerId = currentUser.requireUserId(authentication);
        model.addAttribute("weddingId", id);
        model.addAttribute("wishes", studio.wishes(ownerId, id, status, page));
        model.addAttribute("selectedStatus", status == null ? "" : status.name());
        model.addAttribute("wishStatuses", WishStatus.values());
        return "wedding/wishes";
    }

    @PostMapping("/wedding/{id}/wishes/{wishId}")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String moderateWish(@PathVariable UUID id, @PathVariable UUID wishId, @RequestParam WishStatus status,
                               Authentication authentication, HttpServletRequest request,
                               RedirectAttributes redirectAttributes) {
        studio.moderateWish(currentUser.requireUserId(authentication), id, wishId, status, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "APPROVED".equals(status) ? "Đã duyệt lời chúc thành công."
                : "REJECTED".equals(status) ? "Đã từ chối lời chúc."
                : "HIDDEN".equals(status) ? "Đã ẩn lời chúc." : "Đã cập nhật lời chúc.");
        return "redirect:/wedding/" + id + "/wishes";
    }

    @GetMapping("/wedding/{id}/album")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String album(@PathVariable UUID id, Authentication authentication, Model model) {
        model.addAttribute("weddingId", id);
        model.addAttribute("album", studio.album(currentUser.requireUserId(authentication), id));
        return "wedding/album";
    }

    @PostMapping("/wedding/{id}/album")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String uploadAlbum(@PathVariable UUID id, @RequestPart("image") MultipartFile image,
                              Authentication authentication, HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        studio.uploadAlbum(currentUser.requireUserId(authentication), id, image, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã thêm ảnh vào album.");
        return "redirect:/wedding/" + id + "/album";
    }

    @PostMapping("/wedding/{id}/album/{mediaId}/cover")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String cover(@PathVariable UUID id, @PathVariable UUID mediaId, Authentication authentication,
                        HttpServletRequest request, RedirectAttributes redirectAttributes) {
        studio.makeCover(currentUser.requireUserId(authentication), id, mediaId, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã đặt ảnh bìa.");
        return "redirect:/wedding/" + id + "/album";
    }

    @PostMapping("/wedding/{id}/album/{mediaId}/delete")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String deleteAlbum(@PathVariable UUID id, @PathVariable UUID mediaId, Authentication authentication,
                              HttpServletRequest request, RedirectAttributes redirectAttributes) {
        studio.deleteMedia(currentUser.requireUserId(authentication), id, mediaId, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã xóa ảnh.");
        return "redirect:/wedding/" + id + "/album";
    }

    @GetMapping("/wedding/{id}/timeline")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String timeline(@PathVariable UUID id, Authentication authentication, Model model) {
        model.addAttribute("weddingId", id);
        var timeline = new java.util.ArrayList<>(weddingApi.getTimeline(id, currentUser.requireUserId(authentication)));
        timeline.sort(java.util.Comparator.comparing(com.utewedding.app.dto.response.TimelineItemResponse::startDateTime,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        model.addAttribute("timeline", timeline);
        model.addAttribute("ceremonyTypes", com.utewedding.app.domain.CeremonyType.values());
        return "wedding/timeline";
    }

    @PostMapping("/wedding/{id}/timeline")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String saveTimeline(@PathVariable UUID id,
                               @RequestParam(required = false) java.util.List<String> title,
                               @RequestParam(required = false) java.util.List<String> type,
                               @RequestParam(required = false) java.util.List<String> start,
                               @RequestParam(required = false) java.util.List<String> venue,
                               @RequestParam(required = false) java.util.List<String> address,
                               Authentication authentication, HttpServletRequest request,
                               RedirectAttributes redirectAttributes) {
        try {
            java.util.List<com.utewedding.app.dto.request.TimelineItemRequest> items = new java.util.ArrayList<>();
            int count = java.util.stream.Stream.of(title, type, start, venue, address)
                    .filter(java.util.Objects::nonNull).mapToInt(java.util.List::size).max().orElse(0);
            if (count > 100) throw new com.utewedding.app.exception.BusinessConflictException("Tối đa 100 mốc.");
            for (int index = 0; index < count; index++) {
                String rowTitle = fieldAt(title, index);
                String rawStart = fieldAt(start, index);
                String rowVenue = fieldAt(venue, index);
                String rowAddress = fieldAt(address, index);
                String rawType = fieldAt(type, index);
                boolean empty = blank(rowTitle) && blank(rawStart) && blank(rowVenue)
                        && blank(rowAddress) && blank(rawType);
                if (empty) continue;
                if (blank(rowTitle) || blank(rawStart)) {
                    throw new com.utewedding.app.exception.BusinessConflictException(
                            "Dòng " + (index + 1) + " cần có tên và ngày giờ bắt đầu.");
                }
                java.time.OffsetDateTime when;
                try {
                    when = rawStart.endsWith("Z") || rawStart.contains("+")
                            ? java.time.OffsetDateTime.parse(rawStart)
                            : java.time.LocalDateTime.parse(rawStart).atOffset(java.time.ZoneOffset.ofHours(7));
                } catch (java.time.DateTimeException invalidDate) {
                    throw new com.utewedding.app.exception.BusinessConflictException(
                            "Ngày giờ tại dòng " + (index + 1) + " không hợp lệ.");
                }
                com.utewedding.app.domain.CeremonyType ceremonyType;
                try {
                    ceremonyType = blank(rawType) ? com.utewedding.app.domain.CeremonyType.THANH_HON
                            : com.utewedding.app.domain.CeremonyType.valueOf(rawType.trim());
                } catch (IllegalArgumentException invalidType) {
                    throw new com.utewedding.app.exception.BusinessConflictException(
                            "Loại sự kiện tại dòng " + (index + 1) + " không hợp lệ.");
                }
                items.add(new com.utewedding.app.dto.request.TimelineItemRequest(null,
                        ceremonyType,
                        rowTitle,
                        when,
                        null, "Asia/Ho_Chi_Minh",
                        rowVenue,
                        rowAddress,
                        null, null, index, true));
            }
            weddingApi.updateTimeline(id, currentUser.requireUserId(authentication),
                    new com.utewedding.app.dto.request.UpdateTimelineRequest(items), context(request));
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu dòng thời gian.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/wedding/" + id + "/timeline";
    }

    private static String fieldAt(java.util.List<String> values, int index) {
        return values == null || values.size() <= index ? null : values.get(index);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String lifecycle(UUID id, Authentication authentication, HttpServletRequest request,
                             RedirectAttributes redirectAttributes, String action) {
        try {
            UUID ownerId = currentUser.requireUserId(authentication);
            RequestContext context = context(request);
            if ("publish".equals(action)) studio.publish(ownerId, id, context);
            else if ("unpublish".equals(action)) studio.unpublish(ownerId, id, context);
            else studio.archive(ownerId, id, context);
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật trạng thái thiệp.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
            if ("publish".equals(action) && exception instanceof WeddingPublishValidationException validation) {
                String step = validation.editorStep().id();
                redirectAttributes.addFlashAttribute("errorActionUrl",
                        "/wedding/" + id + "/edit?step=" + step);
                redirectAttributes.addFlashAttribute("errorActionLabel",
                        validation.editorStep().actionLabel());
            }
        }
        return "redirect:/wedding";
    }

    private static String editorStepForErrors(BindingResult bindingResult, String submittedStep) {
        for (org.springframework.validation.FieldError error : bindingResult.getFieldErrors()) {
            switch (error.getField()) {
                case "brideName", "groomName", "story", "slug" -> { return "couple"; }
                case "weddingDate", "weddingTime", "venue", "address", "mapUrl" -> { return "event"; }
                case "coverImage", "coverImageUrl" -> { return "media"; }
                default -> { }
            }
        }
        return normalizeEditorStep(submittedStep);
    }

    private static String normalizeEditorStep(String step) {
        return switch (step == null ? "" : step) {
            case "event", "media", "guests" -> step;
            default -> "couple";
        };
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
