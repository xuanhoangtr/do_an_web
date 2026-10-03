package com.utewedding.app.service.web;

import com.utewedding.app.domain.WishStatus;
import com.utewedding.app.domain.WeddingStatus;
import com.utewedding.app.domain.entity.TemplateEntity;
import com.utewedding.app.domain.entity.WeddingDraftEntity;
import com.utewedding.app.domain.entity.WeddingCeremonyEntity;
import com.utewedding.app.domain.entity.WeddingEntity;
import com.utewedding.app.domain.MediaAssetKind;
import com.utewedding.app.domain.MediaAssetStatus;
import com.utewedding.app.domain.entity.MediaAssetEntity;
import com.utewedding.app.dto.request.CreateWeddingRequest;
import com.utewedding.app.dto.request.SubmitWishRequest;
import com.utewedding.app.dto.request.UpdateWeddingRequest;
import com.utewedding.app.domain.RsvpStatus;
import com.utewedding.app.domain.entity.GuestEntity;
import com.utewedding.app.domain.entity.RsvpEntity;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.TemplateRepository;
import com.utewedding.app.repository.GuestRepository;
import com.utewedding.app.repository.RsvpRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.repository.WishRepository;
import com.utewedding.app.repository.WeddingCeremonyRepository;
import com.utewedding.app.repository.WeddingDraftRepository;
import com.utewedding.app.repository.MediaAssetRepository;
import com.utewedding.app.service.PublicWishService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.WeddingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class JpaWebWeddingService implements WebWeddingService {

    private final WeddingService weddingService;
    private final WeddingRepository weddingRepository;
    private final TemplateRepository templateRepository;
    private final WeddingDraftRepository draftRepository;
    private final WeddingCeremonyRepository ceremonyRepository;
    private final GuestRepository guestRepository;
    private final RsvpRepository rsvpRepository;
    private final WishRepository wishRepository;
    private final PublicWishService publicWishService;
    private final MediaAssetRepository mediaAssetRepository;
    private final TextSanitizer textSanitizer;

    @org.springframework.beans.factory.annotation.Autowired
    public JpaWebWeddingService(WeddingService weddingService,
                                WeddingRepository weddingRepository,
                                TemplateRepository templateRepository,
                                WeddingDraftRepository draftRepository,
                                WeddingCeremonyRepository ceremonyRepository,
                                GuestRepository guestRepository,
                                RsvpRepository rsvpRepository,
                                WishRepository wishRepository,
                                PublicWishService publicWishService,
                                MediaAssetRepository mediaAssetRepository,
                                TextSanitizer textSanitizer) {
        this.weddingService = Objects.requireNonNull(weddingService, "weddingService");
        this.weddingRepository = Objects.requireNonNull(weddingRepository, "weddingRepository");
        this.templateRepository = Objects.requireNonNull(templateRepository, "templateRepository");
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository");
        this.ceremonyRepository = Objects.requireNonNull(ceremonyRepository, "ceremonyRepository");
        this.guestRepository = Objects.requireNonNull(guestRepository, "guestRepository");
        this.rsvpRepository = Objects.requireNonNull(rsvpRepository, "rsvpRepository");
        this.wishRepository = Objects.requireNonNull(wishRepository, "wishRepository");
        this.publicWishService = Objects.requireNonNull(publicWishService, "publicWishService");
        this.mediaAssetRepository = Objects.requireNonNull(mediaAssetRepository, "mediaAssetRepository");
        this.textSanitizer = Objects.requireNonNull(textSanitizer, "textSanitizer");
    }

    @Override
    public List<WebViewModels.TemplateView> templates() {
        return templateRepository.findAll().stream()
                .filter(TemplateEntity::isActive)
                .map(template -> new WebViewModels.TemplateView(template.getId(), template.getKey(),
                        template.isPremium(), template.getDisplayOrder()))
                .toList();
    }

    @Override
    public List<WebViewModels.WeddingSummaryView> myWeddings(UUID ownerId) {
        return weddingRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(wedding -> {
                    UUID id = wedding.getId();
                    List<GuestEntity> guests = guestRepository.findByWeddingIdAndDeletedAtIsNullOrderByCreatedAtAsc(id);
                    List<RsvpEntity> responses = guests.isEmpty() ? List.of()
                            : rsvpRepository.findByWeddingIdAndGuestIdIn(id,
                                    guests.stream().map(GuestEntity::getId).toList());
                    int attending = (int) responses.stream().filter(row -> row.getAttendanceStatus()
                            == RsvpStatus.ATTENDING).count();
                    int declined = (int) responses.stream().filter(row -> row.getAttendanceStatus()
                            == RsvpStatus.NOT_ATTENDING).count();
                    var events = ceremonyRepository.findByWeddingIdOrderByDisplayOrderAsc(id);
                    var cover = mediaAssetRepository.findByWeddingIdAndStatusOrderByDisplayOrderAscCreatedAtAsc(
                                    id, MediaAssetStatus.READY).stream()
                            .filter(asset -> asset.getKind() == MediaAssetKind.COVER)
                            .map(MediaAssetEntity::getSecureUrl).findFirst().orElse(null);
                    int pendingWishes = wishRepository.findByWeddingIdAndStatusOrderByCreatedAtDesc(
                            id, WishStatus.PENDING).size();
                    return new WebViewModels.WeddingSummaryView(id, wedding.getSlug(),
                            textSanitizer.displayText(wedding.getInternalTitle()),
                            wedding.getStatus(), wedding.getPreviewToken(),
                            events.isEmpty() || events.get(0).getStartDateTime() == null
                                    ? null : localStart(events.get(0)).toLocalDate(),
                            cover, guests.size(), attending, declined,
                            Math.max(0, guests.size() - attending - declined), pendingWishes);
                })
                .toList();
    }

    @Override
    public WebViewModels.WeddingForm form(UUID ownerId, UUID weddingId) {
        if (weddingId == null) return new WebViewModels.WeddingForm();
        WeddingEntity wedding = ownedWedding(ownerId, weddingId);
        return toForm(wedding, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WebViewModels.WeddingForm save(UUID ownerId, WebViewModels.WeddingForm form) {
        if (textSanitizer.trimToNull(form.getBrideName(), 120) == null
                || textSanitizer.trimToNull(form.getGroomName(), 120) == null) {
            throw new BusinessConflictException("Vui lòng nhập tên cô dâu và chú rể bằng văn bản.");
        }
        RequestContext context = new RequestContext(null, null, null);
        UUID weddingId;
        if (form.getId() == null) {
            var created = weddingService.create(ownerId,
                    new CreateWeddingRequest(title(form), null, null), context);
            weddingId = created.id();
        } else {
            weddingService.update(form.getId(), ownerId,
                    new UpdateWeddingRequest(title(form), null), context);
            weddingId = form.getId();
        }
        saveDraft(weddingId, form);
        saveCeremony(weddingId, form);
        saveSlug(ownerId, weddingId, form.getSlug(), title(form));
        return form(ownerId, weddingId);
    }

    @Override
    public WebViewModels.WeddingForm guests(UUID ownerId, UUID weddingId) {
        WeddingEntity wedding = ownedWedding(ownerId, weddingId);
        WebViewModels.WeddingForm form = toForm(wedding, false);
        form.setGuests(guestRepository.findByWeddingIdAndDeletedAtIsNullOrderByCreatedAtAsc(weddingId)
                .stream().map(this::toGuest).toList());
        return form;
    }

    @Override
    public WebViewModels.PublicWeddingView publicWedding(String slug, String guestToken) {
        WeddingEntity wedding = weddingRepository
                .findBySlugAndStatus(slug, WeddingStatus.PUBLISHED).orElse(null);
        if (wedding == null) return new WebViewModels.PublicWeddingView();
        return renderWedding(wedding, guestToken);
    }

    private WebViewModels.PublicWeddingView renderWedding(WeddingEntity wedding, String guestToken) {
        WebViewModels.PublicWeddingView view = new WebViewModels.PublicWeddingView();
        view.setAvailable(true);
        view.setSlug(wedding.getSlug());
        coverUrl(wedding.getId()).ifPresent(view::setCoverImageUrl);
        WeddingDraftEntity draft = draftRepository.findByWeddingId(wedding.getId()).orElse(null);
        if (draft != null) {
            view.setBrideName(draft.getBrideName());
            view.setGroomName(draft.getGroomName());
            view.setCoupleName(joinNames(draft.getBrideName(), draft.getGroomName()));
            view.setStory(firstNonBlank(draft.getInvitationMessage(), draft.getHeadline()));
        }
        if (guestToken != null && !guestToken.isBlank()) {
            GuestEntity guest = guestRepository.findByWeddingIdAndTokenAndDeletedAtIsNull(
                    wedding.getId(), guestToken).orElse(null);
            if (guest == null) {
                WebViewModels.PublicWeddingView hidden = new WebViewModels.PublicWeddingView();
                hidden.setInvalidGuest(true);
                return hidden;
            }
            view.setGuestName(guest.getName());
            view.setRsvpInviteeCount(Math.max(1, Math.min(guest.getMaxAttendees(), 20)));
            rsvpRepository.findByGuestId(guest.getId()).ifPresent(rsvp -> {
                view.setRsvpStatus(rsvp.getAttendanceStatus() == null ? null : rsvp.getAttendanceStatus().name());
                view.setRsvpAttendees(rsvp.getAttendeeCount());
                view.setRsvpNote(rsvp.getNote());
            });
        }
        ceremonyRepository.findByWeddingIdOrderByDisplayOrderAsc(wedding.getId()).stream()
                .findFirst()
                .ifPresent(ceremony -> {
                    if (ceremony.getStartDateTime() != null) {
                        LocalDateTime localStart = localStart(ceremony);
                        view.setWeddingDate(localStart.toLocalDate());
                        view.setWeddingTime(localStart.toLocalTime());
                    }
                    view.setVenue(ceremony.getVenueName());
                    view.setAddress(ceremony.getVenueAddress());
                    view.setMapUrl(ceremony.getGoogleMapsUrl());
                });
        view.setWishes(wishRepository
                .findByWeddingIdAndStatusOrderByCreatedAtDesc(
                        wedding.getId(), WishStatus.APPROVED)
                .stream().map(item -> item.getSenderName() + ": " + item.getMessage()).toList());
        return view;
    }

    @Override
    public WebViewModels.PublicWeddingView previewByToken(String previewToken) {
        WebViewModels.PublicWeddingView hidden = new WebViewModels.PublicWeddingView();
        if (previewToken == null || previewToken.isBlank()) {
            hidden.setInvalidGuest(true);
            return hidden;
        }
        WeddingEntity wedding = weddingRepository.findByPreviewToken(previewToken.trim()).orElse(null);
        if (wedding == null || wedding.getStatus() == WeddingStatus.ARCHIVED
                || wedding.getStatus() == WeddingStatus.SUSPENDED) {
            hidden.setInvalidGuest(true);
            return hidden;
        }
        WebViewModels.PublicWeddingView view = renderWedding(wedding, null);
        view.setPreview(true);
        view.setAvailable(true);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitRsvp(String slug, WebViewModels.RsvpForm form) {
        WeddingEntity wedding = weddingRepository.findBySlugAndStatus(
                        slug, WeddingStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));
        GuestEntity guest;
        if (form.getToken() == null || form.getToken().isBlank()) {
            String name = textSanitizer.trimToNull(form.getGuestName(), 160);
            if (name == null) throw new BusinessConflictException("Vui lòng nhập họ và tên để gửi phản hồi.");
            String responseId;
            try { responseId = UUID.fromString(form.getPublicResponseId()).toString(); }
            catch (RuntimeException exception) {
                throw new BusinessConflictException("Vui lòng tải lại thiệp rồi gửi phản hồi.");
            }
            String publicToken = "public-" + responseId;
            guest = guestRepository.findByWeddingIdAndTokenAndDeletedAtIsNull(wedding.getId(), publicToken)
                    .orElseGet(() -> {
                        GuestEntity created = new GuestEntity(wedding.getId(), name, publicToken);
                        created.setMaxAttendees(20);
                        created.setGroupName("Phản hồi từ liên kết công khai");
                        return guestRepository.save(created);
                    });
            form.setToken(publicToken);
        } else {
            guest = guestRepository.findByWeddingIdAndTokenAndDeletedAtIsNull(wedding.getId(), form.getToken())
                    .orElseThrow(() -> new ResourceNotFoundException("Liên kết khách mời không còn hợp lệ."));
        }
        RsvpEntity rsvp = rsvpRepository.findByGuestId(guest.getId()).orElseGet(RsvpEntity::new);
        rsvp.setWeddingId(wedding.getId());
        rsvp.setGuestId(guest.getId());
        RsvpStatus attendanceStatus = parseRsvpStatus(form.getAttendanceStatus());
        int inviteeCount = Math.max(1, Math.min(guest.getMaxAttendees(), 20));
        rsvp.setAttendanceStatus(attendanceStatus);
        rsvp.setInviteeCount(inviteeCount);
        rsvp.setAttendeeCount(attendanceStatus == RsvpStatus.ATTENDING
                ? Math.max(1, Math.min(form.getAttendeeCount(), inviteeCount)) : 0);
        rsvp.setGuestName(guest.getName());
        rsvp.setNote(textSanitizer.trimToNull(form.getNote(), 4096));
        rsvpRepository.save(rsvp);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitWish(String slug, String senderName, String message) {
        WeddingEntity wedding = weddingRepository.findBySlugAndStatus(
                        slug, WeddingStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));
        publicWishService.submit(wedding.getId(), new SubmitWishRequest(senderName, message));
    }

    private WeddingEntity ownedWedding(UUID ownerId, UUID weddingId) {
        return weddingRepository.findByIdAndOwnerId(weddingId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));
    }

    private WebViewModels.WeddingForm toForm(WeddingEntity wedding, boolean includeGuests) {
        WebViewModels.WeddingForm form = new WebViewModels.WeddingForm();
        form.setId(wedding.getId());
        form.setSlug(wedding.getSlug());
        form.setPreviewToken(wedding.getPreviewToken());
        form.setStatus(wedding.getStatus());
        coverUrl(wedding.getId()).ifPresent(form::setCoverImageUrl);
        WeddingDraftEntity draft = draftRepository.findByWeddingId(wedding.getId()).orElse(null);
        if (draft != null) {
            form.setBrideName(draft.getBrideName());
            form.setGroomName(draft.getGroomName());
            form.setStory(firstNonBlank(draft.getInvitationMessage(), draft.getHeadline()));
        }
        ceremonyRepository.findByWeddingIdOrderByDisplayOrderAsc(wedding.getId()).stream()
                .findFirst()
                .ifPresent(ceremony -> {
                    OffsetDateTime start = ceremony.getStartDateTime();
                    if (start != null) {
                        LocalDateTime localStart = localStart(ceremony);
                        form.setWeddingDate(localStart.toLocalDate());
                        form.setWeddingTime(localStart.toLocalTime());
                    }
                    form.setVenue(ceremony.getVenueName());
                    form.setAddress(ceremony.getVenueAddress());
                    form.setMapUrl(ceremony.getGoogleMapsUrl());
                });
        if (includeGuests) {
            form.setGuests(guestRepository.findByWeddingIdAndDeletedAtIsNullOrderByCreatedAtAsc(wedding.getId())
                    .stream().map(this::toGuest).toList());
        }
        return form;
    }

    private java.util.Optional<String> coverUrl(UUID weddingId) {
        return mediaAssetRepository.findFirstByWeddingIdAndKindAndStatusOrderByCreatedAtDesc(
                        weddingId, MediaAssetKind.COVER, MediaAssetStatus.READY)
                .map(MediaAssetEntity::getSecureUrl);
    }

    private void saveDraft(UUID weddingId, WebViewModels.WeddingForm form) {
        WeddingDraftEntity draft = draftRepository.findByWeddingId(weddingId).orElseGet(() -> {
            WeddingDraftEntity created = new WeddingDraftEntity();
            created.setWeddingId(weddingId);
            return created;
        });
        draft.setBrideName(textSanitizer.trimToNull(form.getBrideName(), 120));
        draft.setGroomName(textSanitizer.trimToNull(form.getGroomName(), 120));
        draft.setInvitationMessage(textSanitizer.trimToNull(form.getStory(), 3000));
        draftRepository.save(draft);
    }

    private void saveSlug(UUID ownerId, UUID weddingId, String requestedSlug, String title) {
        WeddingEntity wedding = ownedWedding(ownerId, weddingId);
        String slug = normaliseSlug(requestedSlug);
        if (slug == null) slug = slugify(title) + "-" + weddingId.toString().substring(0, 8);
        var existing = weddingRepository.findBySlug(slug);
        if (existing.isPresent() && !weddingId.equals(existing.get().getId())) {
            throw new BusinessConflictException("Đường dẫn thiệp đã được sử dụng.");
        }
        wedding.setSlug(slug);
        weddingRepository.save(wedding);
    }

    private static String normaliseSlug(String value) {
        if (value == null || value.isBlank()) return null;
        String slug = java.text.Normalizer.normalize(value.trim().toLowerCase(),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isBlank()) return null;
        return slug.substring(0, Math.min(60, slug.length()));
    }

    private static String slugify(String value) {
        String slug = normaliseSlug(value);
        return slug == null ? "thiep-cuoi" : slug;
    }

    private void saveCeremony(UUID weddingId, WebViewModels.WeddingForm form) {
        WeddingCeremonyEntity ceremony = ceremonyRepository.findByWeddingIdOrderByDisplayOrderAsc(weddingId)
                .stream().findFirst().orElse(null);
        LocalDate date = form.getWeddingDate();
        LocalTime time = form.getWeddingTime();
        if (ceremony != null && ceremony.getStartDateTime() != null) {
            LocalDateTime current = localStart(ceremony);
            date = date == null ? current.toLocalDate() : date;
            time = time == null ? current.toLocalTime() : time;
        }
        if (date == null) {
            return;
        }
        if (time == null) {
            time = LocalTime.MIDNIGHT;
        }
        if (ceremony == null) {
            ceremony = new WeddingCeremonyEntity();
            ceremony.setWeddingId(weddingId);
            ceremony.setDisplayOrder(0);
            ceremony.setVisible(true);
        }
        ceremony.setType(com.utewedding.app.domain.CeremonyType.THANH_HON);
        String ceremonyTitle = textSanitizer.trimToNull(title(form), 160);
        ceremony.setTitle(ceremonyTitle == null ? "Lễ cưới" : ceremonyTitle);
        ceremony.setStartDateTime(LocalDateTime.of(date, time)
                .atZone(ZoneId.of(ceremony.getTimezone())).toOffsetDateTime());
        ceremony.setVenueName(textSanitizer.trimToNull(form.getVenue(), 200));
        ceremony.setVenueAddress(textSanitizer.trimToNull(form.getAddress(), 500));
        ceremony.setGoogleMapsUrl(textSanitizer.requireHttpUrlOrNull(form.getMapUrl(), 2048,
                "URL bản đồ"));
        ceremonyRepository.save(ceremony);
    }

    private WebViewModels.GuestView toGuest(GuestEntity guest) {
        return new WebViewModels.GuestView(guest.getId(), guest.getName(), guest.getGroupName(), guest.getToken());
    }

    private String title(WebViewModels.WeddingForm form) {
        String bride = textSanitizer.trimToNull(form.getBrideName(), 120);
        String groom = textSanitizer.trimToNull(form.getGroomName(), 120);
        String sanitizedTitle = joinNames(bride, groom);
        return sanitizedTitle == null || sanitizedTitle.isBlank() ? "Cô dâu & Chú rể" : sanitizedTitle;
    }

    private static LocalDateTime localStart(WeddingCeremonyEntity ceremony) {
        return ceremony.getStartDateTime().atZoneSameInstant(ZoneId.of(ceremony.getTimezone()))
                .toLocalDateTime();
    }

    private static String joinNames(String bride, String groom) {
        String left = bride == null ? "" : bride.trim();
        String right = groom == null ? "" : groom.trim();
        String value = (left + " & " + right).trim();
        return value.equals("&") ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static RsvpStatus parseRsvpStatus(String value) {
        if (value == null) return RsvpStatus.ATTENDING;
        try { return RsvpStatus.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return RsvpStatus.ATTENDING; }
    }
}
