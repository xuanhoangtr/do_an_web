package com.utewedding.app.service.web;

import com.utewedding.app.domain.MediaAssetKind;
import com.utewedding.app.domain.MediaAssetStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.WeddingStatus;
import com.utewedding.app.domain.WishStatus;
import com.utewedding.app.domain.entity.GuestEntity;
import com.utewedding.app.domain.entity.MediaAssetEntity;
import com.utewedding.app.domain.entity.RsvpEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.domain.entity.WeddingCeremonyEntity;
import com.utewedding.app.domain.entity.WeddingDraftEntity;
import com.utewedding.app.domain.entity.WeddingEntity;
import com.utewedding.app.domain.entity.WishEntity;
import com.utewedding.app.dto.request.MediaUploadRequest;
import com.utewedding.app.dto.request.ModerateWishRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.WeddingPublishValidationException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.GuestRepository;
import com.utewedding.app.repository.MediaAssetRepository;
import com.utewedding.app.repository.RsvpRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.repository.WeddingCeremonyRepository;
import com.utewedding.app.repository.WeddingDraftRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.repository.WishRepository;
import com.utewedding.app.service.MediaService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.WeddingService;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.util.CsvCellEncoder;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WeddingStudioService {
    private final WeddingRepository weddings;
    private final WeddingDraftRepository drafts;
    private final WeddingCeremonyRepository ceremonies;
    private final GuestRepository guests;
    private final RsvpRepository rsvps;
    private final WishRepository wishes;
    private final MediaAssetRepository media;
    private final MediaService mediaService;
    private final WeddingService weddingService;
    private final UserRepository users;
    private final TextSanitizer textSanitizer;
    private final AuditLogPort auditLog;
    private final SecureRandom random = new SecureRandom();

    public WeddingStudioService(WeddingRepository weddings, WeddingDraftRepository drafts,
                                WeddingCeremonyRepository ceremonies, GuestRepository guests,
                                RsvpRepository rsvps, WishRepository wishes, MediaAssetRepository media,
                                MediaService mediaService, WeddingService weddingService,
                                UserRepository users, TextSanitizer textSanitizer, AuditLogPort auditLog) {
        this.weddings = weddings;
        this.drafts = drafts;
        this.ceremonies = ceremonies;
        this.guests = guests;
        this.rsvps = rsvps;
        this.wishes = wishes;
        this.media = media;
        this.mediaService = mediaService;
        this.weddingService = weddingService;
        this.users = users;
        this.textSanitizer = textSanitizer;
        this.auditLog = auditLog;
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(UUID ownerId, UUID weddingId, RequestContext context) {
        WeddingEntity wedding = owned(ownerId, weddingId);
        validateForPublish(wedding);
        wedding.setStatus(WeddingStatus.PUBLISHED);
        wedding.setPublishedAt(Instant.now());
        weddings.save(wedding);
        auditLog.record("WEDDING_PUBLISHED", ownerId, "Wedding", weddingId, java.util.Map.of(), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unpublish(UUID ownerId, UUID weddingId, RequestContext context) {
        WeddingEntity wedding = owned(ownerId, weddingId);
        if (wedding.getStatus() != WeddingStatus.PUBLISHED) {
            throw new BusinessConflictException("Chỉ thiệp đang công khai mới có thể gỡ.");
        }
        wedding.setStatus(WeddingStatus.UNPUBLISHED);
        weddings.save(wedding);
        auditLog.record("WEDDING_UNPUBLISHED", ownerId, "Wedding", weddingId, java.util.Map.of(), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void archive(UUID ownerId, UUID weddingId, RequestContext context) {
        WeddingEntity wedding = owned(ownerId, weddingId);
        wedding.setStatus(WeddingStatus.ARCHIVED);
        weddings.save(wedding);
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId, java.util.Map.of("status", "ARCHIVED"), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reopenArchived(UUID ownerId, UUID weddingId, RequestContext context) {
        WeddingEntity wedding = owned(ownerId, weddingId);
        if (wedding.getStatus() != WeddingStatus.ARCHIVED) {
            throw new BusinessConflictException("Chỉ thiệp đã lưu trữ mới có thể mở lại.");
        }
        WeddingStatus restoredStatus = wedding.getPublishedAt() == null
                ? WeddingStatus.DRAFT : WeddingStatus.UNPUBLISHED;
        wedding.setStatus(restoredStatus);
        weddings.save(wedding);
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId,
                java.util.Map.of("status", restoredStatus.name()), context);
    }

    @Transactional(readOnly = true)
    public List<GuestRow> guestRows(UUID ownerId, UUID weddingId) {
        owned(ownerId, weddingId);
        return guests.findByWeddingIdAndDeletedAtIsNullOrderByCreatedAtAsc(weddingId).stream()
                .map(this::toRow).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void addGuest(UUID ownerId, UUID weddingId, String name, String relationship, Integer maxAttendees,
                         String invitation, RequestContext context) {
        owned(ownerId, weddingId);
        GuestEntity guest = new GuestEntity(weddingId, requiredName(name), newToken());
        guest.setGroupName(textSanitizer.trimToNull(relationship, 120));
        guest.setMaxAttendees(boundAttendees(maxAttendees));
        guest.setInvitationMessageOverride(textSanitizer.trimToNull(invitation, 1000));
        guests.save(guest);
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId, java.util.Map.of("guest", "created"), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateGuest(UUID ownerId, UUID weddingId, UUID guestId, String name, String relationship,
                            Integer maxAttendees, String invitation, RequestContext context) {
        owned(ownerId, weddingId);
        GuestEntity guest = guests.findByIdAndWeddingIdAndDeletedAtIsNull(guestId, weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khách mời."));
        guest.setName(requiredName(name));
        guest.setGroupName(textSanitizer.trimToNull(relationship, 120));
        guest.setMaxAttendees(boundAttendees(maxAttendees));
        guest.setInvitationMessageOverride(textSanitizer.trimToNull(invitation, 1000));
        guests.save(guest);
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId, java.util.Map.of("guest", "updated"), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteGuest(UUID ownerId, UUID weddingId, UUID guestId, RequestContext context) {
        owned(ownerId, weddingId);
        GuestEntity guest = guests.findByIdAndWeddingIdAndDeletedAtIsNull(guestId, weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khách mời."));
        guest.setDeletedAt(Instant.now());
        guests.save(guest);
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId, java.util.Map.of("guest", "deleted"), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public String regenerateToken(UUID ownerId, UUID weddingId, UUID guestId, RequestContext context) {
        owned(ownerId, weddingId);
        GuestEntity guest = guests.findByIdAndWeddingIdAndDeletedAtIsNull(guestId, weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khách mời."));
        guest.setToken(newToken());
        guests.save(guest);
        auditLog.record("GUEST_TOKEN_REGENERATED", ownerId, "Guest", guestId, java.util.Map.of(), context);
        return guest.getToken();
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID ownerId, UUID weddingId) {
        StringBuilder csv = new StringBuilder("name,relationship,maxAttendees,invitation,token,rsvp,attendees,note\n");
        for (GuestRow row : guestRows(ownerId, weddingId)) {
            csv.append(csv(row.name())).append(',').append(csv(row.relationship())).append(',')
                    .append(row.maxAttendees()).append(',').append(csv(row.invitation())).append(',')
                    .append(csv(row.token())).append(',').append(csv(row.rsvpStatus())).append(',')
                    .append(row.attendeeCount() == null ? "" : row.attendeeCount()).append(',')
                    .append(csv(row.note())).append('\n');
        }
        return csv.toString();
    }

    @Transactional(rollbackFor = Exception.class)
    public int importCsv(UUID ownerId, UUID weddingId, String csv, RequestContext context) {
        owned(ownerId, weddingId);
        if (csv == null || csv.isBlank()) throw new BusinessConflictException("Tệp CSV trống.");
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int imported = 0;
        for (String line : lines) {
            if (line.isBlank() || line.toLowerCase(java.util.Locale.ROOT).startsWith("name,")) continue;
            List<String> cells = parseCsvLine(line);
            if (cells.isEmpty() || cells.get(0).isBlank()) continue;
            String name = cells.get(0);
            String relationship = cells.size() > 1 ? cells.get(1) : null;
            Integer max = null;
            if (cells.size() > 2 && !cells.get(2).isBlank()) {
                try { max = Integer.parseInt(cells.get(2).trim()); }
                catch (NumberFormatException exception) {
                    throw new BusinessConflictException("Số người tối đa không hợp lệ ở dòng: " + name);
                }
            }
            String invitation = cells.size() > 3 ? cells.get(3) : null;
            addGuest(ownerId, weddingId, name, relationship, max, invitation, context);
            imported++;
        }
        if (imported == 0) throw new BusinessConflictException("Không có khách mời hợp lệ trong CSV.");
        return imported;
    }

    @Transactional(readOnly = true)
    public List<WishEntity> wishes(UUID ownerId, UUID weddingId) {
        owned(ownerId, weddingId);
        return wishes.findByWeddingIdOrderByCreatedAtDesc(weddingId);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<WishEntity> wishes(UUID ownerId, UUID weddingId,
                                                                    WishStatus status, int page) {
        owned(ownerId, weddingId);
        var pageable = PageRequest.of(Math.max(0, page), 20,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                        .and(org.springframework.data.domain.Sort.by("id")));
        var result = status == null ? wishes.findByWeddingIdOrderByCreatedAtDesc(weddingId, pageable)
                : wishes.findByWeddingIdAndStatusOrderByCreatedAtDesc(weddingId, status, pageable);
        if (page > 0 && result.getTotalPages() > 0 && page >= result.getTotalPages()) {
            var last = PageRequest.of(result.getTotalPages() - 1, pageable.getPageSize(), pageable.getSort());
            result = status == null ? wishes.findByWeddingIdOrderByCreatedAtDesc(weddingId, last)
                    : wishes.findByWeddingIdAndStatusOrderByCreatedAtDesc(weddingId, status, last);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void moderateWish(UUID ownerId, UUID weddingId, UUID wishId, WishStatus status, RequestContext context) {
        weddingService.moderateWish(weddingId, wishId, ownerId, new ModerateWishRequest(status), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void uploadAlbum(UUID ownerId, UUID weddingId, MultipartFile file, RequestContext context) {
        owned(ownerId, weddingId);
        mediaService.upload(ownerId, new MediaUploadRequest(file, weddingId, MediaAssetKind.GALLERY, "Ảnh album", null));
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId, java.util.Map.of("media", "uploaded"), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMedia(UUID ownerId, UUID weddingId, UUID mediaId, RequestContext context) {
        owned(ownerId, weddingId);
        MediaAssetEntity asset = media.findByIdAndWeddingId(mediaId, weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ảnh."));
        media.delete(asset);
        auditLog.record("MEDIA_DELETED", ownerId, "MediaAsset", mediaId, java.util.Map.of(), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void makeCover(UUID ownerId, UUID weddingId, UUID mediaId, RequestContext context) {
        owned(ownerId, weddingId);
        for (MediaAssetEntity asset : media.findByWeddingIdAndStatusOrderByDisplayOrderAscCreatedAtAsc(
                weddingId, MediaAssetStatus.READY)) {
            if (asset.getKind() == MediaAssetKind.COVER) asset.setKind(MediaAssetKind.GALLERY);
            if (asset.getId().equals(mediaId)) asset.setKind(MediaAssetKind.COVER);
            media.save(asset);
        }
        auditLog.record("WEDDING_UPDATED", ownerId, "Wedding", weddingId, java.util.Map.of("cover", mediaId.toString()), context);
    }

    @Transactional(readOnly = true)
    public List<MediaAssetEntity> album(UUID ownerId, UUID weddingId) {
        owned(ownerId, weddingId);
        return media.findByWeddingIdAndStatusOrderByDisplayOrderAscCreatedAtAsc(weddingId, MediaAssetStatus.READY);
    }

    @Transactional(readOnly = true)
    public Page<WeddingEntity> searchForModeration(UUID actorId, String query, WeddingStatus status, int page) {
        requireStaff(actorId);
        return weddings.search(query == null || query.isBlank() ? null : query.trim(), status, PageRequest.of(Math.max(page, 0), 20));
    }

    @Transactional(rollbackFor = Exception.class)
    public void suspend(UUID actorId, UUID weddingId, String reason, RequestContext context) {
        requireStaff(actorId);
        if (reason == null || reason.trim().length() < 10) {
            throw new BusinessConflictException("Lý do tạm ngưng cần ít nhất 10 ký tự.");
        }
        WeddingEntity wedding = weddings.findById(weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp."));
        wedding.setStatus(WeddingStatus.SUSPENDED);
        weddings.save(wedding);
        auditLog.record("WEDDING_SUSPENDED", actorId, "Wedding", weddingId,
                java.util.Map.of("reason", reason.trim()), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void restore(UUID actorId, UUID weddingId, String reason, RequestContext context) {
        requireStaff(actorId);
        WeddingEntity wedding = weddings.findById(weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp."));
        wedding.setStatus(wedding.getPublishedAt() == null ? WeddingStatus.DRAFT : WeddingStatus.PUBLISHED);
        weddings.save(wedding);
        auditLog.record("WEDDING_UNSUSPENDED", actorId, "Wedding", weddingId,
                java.util.Map.of("reason", reason == null ? "" : reason.trim()), context);
    }

    public WeddingEntity ownedView(UUID ownerId, UUID weddingId) {
        return owned(ownerId, weddingId);
    }

    private void validateForPublish(WeddingEntity wedding) {
        if (wedding.getSlug() == null || !wedding.getSlug().matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new WeddingPublishValidationException(
                    "Bước 01 Cặp đôi: đường dẫn thiệp chưa hợp lệ.",
                    WeddingPublishValidationException.EditorStep.COUPLE);
        }
        weddings.findBySlug(wedding.getSlug()).filter(other -> !other.getId().equals(wedding.getId()))
                .ifPresent(other -> { throw new WeddingPublishValidationException(
                        "Bước 01 Cặp đôi: đường dẫn thiệp này đã được sử dụng. Hãy chọn đường dẫn khác.",
                        WeddingPublishValidationException.EditorStep.COUPLE); });
        WeddingDraftEntity draft = drafts.findByWeddingId(wedding.getId())
                .orElseThrow(() -> new WeddingPublishValidationException(
                        "Bước 01 Cặp đôi: cần nhập tên cô dâu và chú rể trước khi xuất bản.",
                        WeddingPublishValidationException.EditorStep.COUPLE));
        if (blank(draft.getBrideName()) || blank(draft.getGroomName())) {
            throw new WeddingPublishValidationException(
                    "Bước 01 Cặp đôi: cần nhập tên cô dâu và chú rể trước khi xuất bản.",
                    WeddingPublishValidationException.EditorStep.COUPLE);
        }
        List<WeddingCeremonyEntity> events = ceremonies.findByWeddingIdOrderByDisplayOrderAsc(wedding.getId());
        if (events.isEmpty()) {
            throw new WeddingPublishValidationException(
                    "Bước 02 Hôn lễ: cần ngày tổ chức và tên địa điểm hoặc địa chỉ. Hãy lưu thiệp trước khi xuất bản; dùng Timeline để thêm các mốc khác.",
                    WeddingPublishValidationException.EditorStep.EVENT);
        }
        boolean validEvent = events.stream().anyMatch(event -> event.getStartDateTime() != null
                && (!blank(event.getVenueName()) || !blank(event.getVenueAddress())));
        if (!validEvent) {
            throw new WeddingPublishValidationException(
                    "Bước 02 Hôn lễ: cần ngày tổ chức và tên địa điểm hoặc địa chỉ. Hãy lưu thiệp trước khi xuất bản; dùng Timeline để thêm các mốc khác.",
                    WeddingPublishValidationException.EditorStep.EVENT);
        }
    }

    private GuestRow toRow(GuestEntity guest) {
        RsvpEntity rsvp = rsvps.findByGuestId(guest.getId()).orElse(null);
        return new GuestRow(guest.getId(), guest.getName(), guest.getGroupName(), guest.getMaxAttendees(),
                guest.getInvitationMessageOverride(), guest.getToken(),
                rsvp == null || rsvp.getAttendanceStatus() == null ? "PENDING" : rsvp.getAttendanceStatus().name(),
                rsvp == null ? null : rsvp.getAttendeeCount(),
                rsvp == null ? null : rsvp.getNote(),
                rsvp == null ? null : rsvp.getUpdatedAt());
    }

    private WeddingEntity owned(UUID ownerId, UUID weddingId) {
        return weddings.findByIdAndOwnerId(weddingId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));
    }

    private void requireStaff(UUID actorId) {
        UserEntity actor = users.findById(actorId).orElseThrow(() -> new AccessDeniedException("Tài khoản không tồn tại."));
        if (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.MANAGER
                && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Bạn không có quyền kiểm duyệt thiệp.");
        }
    }

    private String requiredName(String name) {
        String value = textSanitizer.trimToNull(name, 160);
        if (value == null) throw new BusinessConflictException("Tên khách mời là bắt buộc.");
        return value;
    }

    private int boundAttendees(Integer value) {
        if (value == null) return 2;
        return Math.max(1, Math.min(value, 20));
    }

    private String newToken() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String csv(String value) {
        return CsvCellEncoder.cell(value);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else current.append(ch);
        }
        cells.add(current.toString().trim());
        return cells;
    }

    public record GuestRow(UUID id, String name, String relationship, int maxAttendees, String invitation,
                           String token, String rsvpStatus, Integer attendeeCount, String note,
                           Instant respondedAt) {
    }
}
