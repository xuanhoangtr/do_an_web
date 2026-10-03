package com.utewedding.app.service.impl;

import com.utewedding.app.domain.CeremonyType;
import com.utewedding.app.domain.WeddingStatus;
import com.utewedding.app.domain.WishStatus;
import com.utewedding.app.domain.entity.TemplateEntity;
import com.utewedding.app.domain.entity.WeddingCeremonyEntity;
import com.utewedding.app.domain.entity.WeddingDraftEntity;
import com.utewedding.app.domain.entity.WeddingEntity;
import com.utewedding.app.domain.entity.WeddingSectionSettingEntity;
import com.utewedding.app.domain.entity.WishEntity;
import com.utewedding.app.dto.request.CreateWeddingRequest;
import com.utewedding.app.dto.request.ModerateWishRequest;
import com.utewedding.app.dto.request.TimelineItemRequest;
import com.utewedding.app.dto.request.UpdateTimelineRequest;
import com.utewedding.app.dto.request.UpdateWeddingRequest;
import com.utewedding.app.dto.response.TimelineItemResponse;
import com.utewedding.app.dto.response.WeddingResponse;
import com.utewedding.app.dto.response.WishResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.TemplateRepository;
import com.utewedding.app.repository.WeddingCeremonyRepository;
import com.utewedding.app.repository.WeddingDraftRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.repository.WeddingSectionSettingRepository;
import com.utewedding.app.repository.WishRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.WeddingService;
import com.utewedding.app.service.port.AuditLogPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeddingServiceImpl implements WeddingService {

    private final WeddingRepository weddingRepository;
    private final WeddingDraftRepository draftRepository;
    private final WeddingSectionSettingRepository settingRepository;
    private final WeddingCeremonyRepository ceremonyRepository;
    private final TemplateRepository templateRepository;
    private final WishRepository wishRepository;
    private final AuditLogPort auditLog;
    private final EntityManager entityManager;
    private final String publicSiteBaseUrl;
    private final TextSanitizer textSanitizer;

    public WeddingServiceImpl(WeddingRepository weddingRepository,
                              WeddingDraftRepository draftRepository,
                              WeddingSectionSettingRepository settingRepository,
                              WeddingCeremonyRepository ceremonyRepository,
                              TemplateRepository templateRepository,
                              WishRepository wishRepository,
                              AuditLogPort auditLog,
                              EntityManager entityManager,
                              @Value("${app.public-site-base-url:}") String publicSiteBaseUrl,
                              TextSanitizer textSanitizer) {
        this.weddingRepository = weddingRepository;
        this.draftRepository = draftRepository;
        this.settingRepository = settingRepository;
        this.ceremonyRepository = ceremonyRepository;
        this.templateRepository = templateRepository;
        this.wishRepository = wishRepository;
        this.auditLog = auditLog;
        this.entityManager = entityManager;
        this.publicSiteBaseUrl = stripTrailingSlash(publicSiteBaseUrl);
        this.textSanitizer = textSanitizer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WeddingResponse create(UUID ownerId, CreateWeddingRequest request, RequestContext context) {
        UUID templateId = resolveTemplateId(request.templateId());
        WeddingEntity wedding = new WeddingEntity();
        wedding.setOwnerId(ownerId);
        wedding.setTemplateId(templateId);
        wedding.setEventType(request.eventType() == null
                ? com.utewedding.app.domain.EventType.WEDDING : request.eventType());
        wedding.setStatus(WeddingStatus.DRAFT);
        wedding.setPreviewToken(UUID.randomUUID().toString().replace("-", ""));
        wedding.setInternalTitle(textSanitizer.trimToNull(request.internalTitle(), 160));
        wedding = weddingRepository.save(wedding);

        WeddingDraftEntity draft = new WeddingDraftEntity();
        draft.setWeddingId(wedding.getId());
        draftRepository.save(draft);

        WeddingSectionSettingEntity settings = new WeddingSectionSettingEntity();
        settings.setWeddingId(wedding.getId());
        settingRepository.save(settings);

        auditLog.record("WEDDING_CREATED", ownerId, "Wedding", wedding.getId(), Map.of(), context);
        return toResponse(wedding);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WeddingResponse update(UUID weddingId, UUID ownerId, UpdateWeddingRequest request,
                                  RequestContext context) {
        WeddingEntity wedding = ownedWedding(weddingId, ownerId);
        if (request.templateId() != null) {
            resolveTemplateId(request.templateId());
            wedding.setTemplateId(request.templateId());
        }
        if (request.internalTitle() != null) {
            wedding.setInternalTitle(textSanitizer.trimToNull(request.internalTitle(), 160));
        }
        wedding = weddingRepository.save(wedding);
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId,
                Map.of("version", wedding.getVersion()), context);
        return toResponse(wedding);
    }

    @Override
    @Transactional(readOnly = true)
    public WeddingResponse get(UUID weddingId, UUID ownerId) {
        return toResponse(ownedWedding(weddingId, ownerId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimelineItemResponse> getTimeline(UUID weddingId, UUID ownerId) {
        ownedWedding(weddingId, ownerId);
        return ceremonyRepository.findByWeddingIdOrderByDisplayOrderAsc(weddingId)
                .stream().map(this::toTimelineResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<TimelineItemResponse> updateTimeline(UUID weddingId, UUID ownerId,
                                                     UpdateTimelineRequest request,
                                                     RequestContext context) {
        WeddingEntity wedding = ownedWedding(weddingId, ownerId);
        if (request == null) throw new BusinessConflictException("Dòng thời gian không hợp lệ.");
        List<TimelineItemRequest> items = request.items() == null ? List.of() : request.items();
        if (items.size() > 100) throw new BusinessConflictException("Tối đa 100 mốc.");

        if (request.version() != null && wedding.getVersion() != request.version()) {
            throw new BusinessConflictException(
                    "Nội dung đã được cập nhật ở nơi khác. Vui lòng tải lại trước khi lưu.");
        }

        // Build and validate the complete replacement before touching stored rows.
        List<WeddingCeremonyEntity> entities = new java.util.ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            TimelineItemRequest requestItem = items.get(index);
            if (requestItem == null || requestItem.startDateTime() == null
                    || requestItem.title() == null || requestItem.title().isBlank()) {
                throw new BusinessConflictException("Mỗi mốc cần có tên và ngày giờ bắt đầu.");
            }
            if (requestItem.endDateTime() != null
                    && requestItem.endDateTime().isBefore(requestItem.startDateTime())) {
                throw new BusinessConflictException("Thời gian kết thúc phải sau thời gian bắt đầu.");
            }
            WeddingCeremonyEntity entity = new WeddingCeremonyEntity();
            entity.setWeddingId(weddingId);
            entity.setType(requestItem.type() == null ? CeremonyType.THANH_HON : requestItem.type());
            entity.setTitle(textSanitizer.trimToNull(requestItem.title(), 160));
            entity.setStartDateTime(requestItem.startDateTime());
            entity.setEndDateTime(requestItem.endDateTime());
            entity.setTimezone(requestItem.timezone() == null
                    ? "Asia/Ho_Chi_Minh" : textSanitizer.trimToNull(requestItem.timezone(), 64));
            entity.setVenueName(textSanitizer.trimToNull(requestItem.venueName(), 200));
            entity.setVenueAddress(textSanitizer.trimToNull(requestItem.venueAddress(), 500));
            entity.setGoogleMapsUrl(textSanitizer.requireHttpUrlOrNull(requestItem.googleMapsUrl(), 2048,
                    "Google Maps URL"));
            entity.setDescription(textSanitizer.trimToNull(requestItem.description(), 2000));
            entity.setDisplayOrder(requestItem.displayOrder() == null
                    ? index : requestItem.displayOrder());
            entity.setVisible(requestItem.visible() == null || requestItem.visible());
            entities.add(entity);
        }

        // Use only JPA @Version for conflict detection. FORCE_INCREMENT also
        // versions a replace-all child update whose parent scalar fields stay
        // unchanged during this request.
        entityManager.lock(wedding, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        // The editor contract is replace-all: rows omitted by a valid request are removed.
        ceremonyRepository.deleteByWeddingId(weddingId);
        List<WeddingCeremonyEntity> saved = ceremonyRepository.saveAll(entities);
        auditLog.record("WEDDING_TIMELINE_UPDATED", ownerId, "Wedding", weddingId,
                Map.of("version", wedding.getVersion(), "items", saved.size()), context);
        return saved.stream().map(this::toTimelineResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WishResponse moderateWish(UUID weddingId, UUID wishId, UUID ownerId,
                                     ModerateWishRequest request, RequestContext context) {
        requireOwnedWedding(weddingId, ownerId);
        WishEntity wish = wishRepository.findByIdAndWeddingId(wishId, weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lời chúc."));
        WishStatus status = request.status();
        if (status == WishStatus.PENDING) {
            throw new BusinessConflictException("Lời chúc đã duyệt chỉ có thể hiện, ẩn hoặc từ chối.");
        }
        wish.setStatus(status);
        wish.setApprovedAt(status == WishStatus.APPROVED ? Instant.now() : null);
        wish.setModeratedById(ownerId);
        wishRepository.save(wish);
        auditLog.record("WISH_MODERATED", ownerId, "Wish", wishId,
                Map.of("status", status.name(), "weddingId", weddingId.toString()), context);
        return new WishResponse(wish.getId(), wish.getStatus(), wish.getApprovedAt());
    }

    private UUID resolveTemplateId(UUID requestedId) {
        if (requestedId != null) {
            return templateRepository.findByIdAndActiveTrue(requestedId)
                    .map(TemplateEntity::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mẫu giao diện."));
        }
        return templateRepository.findFirstByActiveTrueAndPremiumFalseOrderByDisplayOrderAsc()
                .map(TemplateEntity::getId).orElse(null);
    }

    private WeddingEntity requireWedding(UUID weddingId) {
        return weddingRepository.findById(weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));
    }

    private WeddingEntity ownedWedding(UUID weddingId, UUID ownerId) {
        return weddingRepository.findByIdAndOwnerId(weddingId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));
    }

    private void requireOwnedWedding(UUID weddingId, UUID ownerId) {
        ownedWedding(weddingId, ownerId);
    }

    private WeddingResponse toResponse(WeddingEntity wedding) {
        WeddingDraftEntity draft = draftRepository.findByWeddingId(wedding.getId()).orElse(null);
        TemplateEntity template = wedding.getTemplateId() == null ? null
                : templateRepository.findById(wedding.getTemplateId()).orElse(null);
        Instant firstCeremonyAt = ceremonyRepository.findByWeddingIdOrderByDisplayOrderAsc(wedding.getId())
                .stream().map(WeddingCeremonyEntity::getStartDateTime).filter(java.util.Objects::nonNull)
                .min(OffsetDateTime::compareTo).map(OffsetDateTime::toInstant).orElse(null);
        String previewUrl = publicSiteBaseUrl + "/preview/" + wedding.getPreviewToken();
        String publicUrl = wedding.getSlug() == null
                || wedding.getStatus() != WeddingStatus.PUBLISHED ? null
                : publicSiteBaseUrl + "/" + wedding.getSlug();
        return new WeddingResponse(wedding.getId(), wedding.getInternalTitle(), wedding.getEventType(),
                wedding.getStatus(), wedding.getSlug(), wedding.getTemplateId(),
                template == null ? null : template.getKey(), wedding.getVersion(),
                draft == null ? null : draft.getBrideName(), draft == null ? null : draft.getGroomName(),
                firstCeremonyAt, wedding.getPublishedAt(), wedding.getCreatedAt(), wedding.getUpdatedAt(),
                previewUrl, publicUrl);
    }

    private TimelineItemResponse toTimelineResponse(WeddingCeremonyEntity item) {
        String timezone = item.getTimezone();
        return new TimelineItemResponse(item.getId(), item.getType(), item.getTitle(),
                inTimezone(item.getStartDateTime(), timezone),
                inTimezone(item.getEndDateTime(), timezone), timezone, item.getVenueName(), item.getVenueAddress(),
                item.getGoogleMapsUrl(), item.getDescription(), item.getDisplayOrder(), item.isVisible());
    }

    private static OffsetDateTime inTimezone(OffsetDateTime value, String timezone) {
        if (value == null) return null;
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        if (timezone != null && !timezone.isBlank()) {
            try {
                zone = ZoneId.of(timezone);
            } catch (DateTimeException ignored) {
                // Keep legacy events with an invalid zone readable in the default wedding timezone.
            }
        }
        return value.atZoneSameInstant(zone).toOffsetDateTime();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
