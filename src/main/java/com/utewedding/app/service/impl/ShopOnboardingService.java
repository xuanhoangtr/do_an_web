package com.utewedding.app.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ShopApplicationStatus;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopMembershipRole;
import com.utewedding.app.domain.ShopMembershipStatus;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.ShopApplicationEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.ShopMembershipEntity;
import com.utewedding.app.domain.entity.ShopStatusHistoryEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.ShopApplicationForm;
import com.utewedding.app.dto.response.ShopAdminDetailView;
import com.utewedding.app.dto.response.ShopApplicationAttemptView;
import com.utewedding.app.dto.response.ShopApplicationSnapshot;
import com.utewedding.app.dto.response.ShopOnboardingView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ShopApplicationRepository;
import com.utewedding.app.repository.ShopMembershipRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.ShopStatusHistoryRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.integration.NotificationService;
import com.utewedding.app.security.WebSessionRevocationService;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.SessionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional seller application, admin review, and shop status workflow. */
@Service
public class ShopOnboardingService {
    private static final int PAGE_SIZE = 20;
    private static final String APPLICATION_BLOCKED = "Hồ sơ hiện không thể gửi ở trạng thái này.";

    private final ShopRepository shops;
    private final ShopMembershipRepository memberships;
    private final ShopApplicationRepository applications;
    private final ShopStatusHistoryRepository history;
    private final UserRepository users;
    private final TextSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final MarketplaceFeatureFlags features;
    private final EntityManager entityManager;
    private final SessionService sessions;
    private final WebSessionRevocationService webSessions;
    private NotificationService notifications;

    public ShopOnboardingService(
            ShopRepository shops,
            ShopMembershipRepository memberships,
            ShopApplicationRepository applications,
            ShopStatusHistoryRepository history,
            UserRepository users,
            TextSanitizer sanitizer,
            ObjectMapper objectMapper,
            MarketplaceFeatureFlags features,
            EntityManager entityManager,
            SessionService sessions,
            WebSessionRevocationService webSessions) {
        this.shops = shops;
        this.memberships = memberships;
        this.applications = applications;
        this.history = history;
        this.users = users;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.features = features;
        this.entityManager = entityManager;
        this.sessions = sessions;
        this.webSessions = webSessions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachNotificationService(NotificationService service) {
        this.notifications = service;
    }

    @Transactional(readOnly = true)
    public ShopOnboardingView onboardingFor(UUID userId) {
        requireFeature(Feature.ONBOARDING);
        UserEntity user = requireCustomer(userId);
        ShopMembershipEntity owner = activeOwner(userId).orElse(null);
        if (owner == null) {
            boolean revoked = memberships.findByUserIdAndStatusOrderByCreatedAtDesc(
                            userId, ShopMembershipStatus.REVOKED).stream()
                    .anyMatch(row -> row.getRole() == ShopMembershipRole.OWNER);
            ShopApplicationForm empty = new ShopApplicationForm();
            empty.setShopName(user.getDisplayName());
            return new ShopOnboardingView(null, null, empty, List.of(), !revoked, revoked, false);
        }

        ShopEntity shop = shops.findById(owner.getShopId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        if (shop.getKind() != ShopKind.VENDOR) {
            throw new BusinessConflictException(APPLICATION_BLOCKED);
        }
        ShopApplicationEntity latest = applications.findTopByShopIdOrderByAttemptNumberDesc(shop.getId())
                .orElse(null);
        ShopApplicationForm form = latest == null ? new ShopApplicationForm() : toForm(readSnapshot(latest));
        if (latest == null) form.setShopName(shop.getDisplayName());
        boolean rejected = shop.getStatus() == ShopStatus.REJECTED
                && latest != null && latest.getStatus() == ShopApplicationStatus.REJECTED;
        boolean canSubmit = rejected;
        boolean canOpenVendorWorkspace = canOpenVendorWorkspace(user, shop, owner);
        return new ShopOnboardingView(shop, latest, form,
                history.findByShopIdOrderByOccurredAtDescIdDesc(shop.getId()), canSubmit, false,
                canOpenVendorWorkspace);
    }

    /**
     * First submission creates the shop, OWNER membership, application, and initial status history in one
     * transaction. Resubmission after rejection preserves the same shop and OWNER identity and adds an attempt.
     */
    @Transactional(rollbackFor = Exception.class)
    public ShopEntity submit(UUID userId, ShopApplicationForm form) {
        requireFeature(Feature.ONBOARDING);
        if (form == null) throw new BusinessConflictException("Hồ sơ đăng ký không hợp lệ.");

        // The user row serializes first-time requests for one person. Database partial unique indexes remain
        // the final guard if a caller bypasses this workflow or two deployments race.
        UserEntity user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        requireCustomer(user);
        ShopApplicationSnapshot snapshot = normalizeAndSnapshot(form);

        List<ShopMembershipEntity> activeOwners = memberships.findByUserIdAndRoleAndStatus(
                userId, ShopMembershipRole.OWNER, ShopMembershipStatus.ACTIVE);
        if (activeOwners.size() > 1) {
            throw new BusinessConflictException("Tài khoản có nhiều cửa hàng chủ sở hữu cần được quản trị viên kiểm tra.");
        }
        if (activeOwners.isEmpty()) {
            boolean hasRevokedOwner = memberships.findByUserIdAndStatusOrderByCreatedAtDesc(
                            userId, ShopMembershipStatus.REVOKED).stream()
                    .anyMatch(row -> row.getRole() == ShopMembershipRole.OWNER);
            if (hasRevokedOwner) {
                throw new BusinessConflictException("Quyền chủ sở hữu đã bị thu hồi. Vui lòng liên hệ hỗ trợ.");
            }
            return createInitialApplication(user, snapshot);
        }

        ShopMembershipEntity owner = activeOwners.get(0);
        ShopEntity shop = shops.findByIdForUpdate(owner.getShopId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        requireVendorShop(shop);
        ShopApplicationEntity previous = applications.findTopByShopIdOrderByAttemptNumberDesc(shop.getId())
                .orElse(null);
        if (shop.getStatus() != ShopStatus.REJECTED || previous == null
                || previous.getStatus() != ShopApplicationStatus.REJECTED) {
            throw new BusinessConflictException(submissionMessage(shop.getStatus()));
        }

        int nextAttempt;
        try {
            nextAttempt = Math.addExact(previous.getAttemptNumber(), 1);
        } catch (ArithmeticException exception) {
            throw new BusinessConflictException("Không thể tạo thêm lần nộp hồ sơ.");
        }
        ShopStatus previousStatus = shop.getStatus();
        shop.setDisplayName(snapshot.shopName());
        shop.setStatus(ShopStatus.PENDING);
        shops.save(shop);
        ShopApplicationEntity attempt = saveSubmittedAttempt(shop, userId, nextAttempt, snapshot);
        history.save(new ShopStatusHistoryEntity(shop.getId(), previousStatus, ShopStatus.PENDING,
                userId, attempt.getId(), null));
        return shop;
    }

    @Transactional(readOnly = true)
    public Page<ShopEntity> listForAdmin(UUID actorId, ShopStatus status, String query, int requestedPage) {
        requireFeature(Feature.VENDOR_MANAGEMENT);
        requireAdmin(actorId);
        String normalizedQuery = sanitizer.trimToNull(query, 100);
        String needle = normalizedQuery == null ? null : normalizedQuery.toLowerCase(Locale.ROOT);
        List<ShopEntity> filtered = shops.findByKind(ShopKind.VENDOR).stream()
                .filter(shop -> status == null || shop.getStatus() == status)
                .filter(shop -> needle == null || shop.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)
                        || shop.getCode().toLowerCase(Locale.ROOT).contains(needle)
                        || shop.getSlug().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(ShopEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ShopEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int pageNumber = Math.max(0, requestedPage);
        int totalPages = (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (totalPages > 0 && pageNumber >= totalPages) pageNumber = totalPages - 1;
        int from = Math.min(pageNumber * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        return new PageImpl<>(filtered.subList(from, to),
                PageRequest.of(pageNumber, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")), filtered.size());
    }

    @Transactional(readOnly = true)
    public ShopAdminDetailView adminDetail(UUID shopId, UUID actorId) {
        requireFeature(Feature.VENDOR_MANAGEMENT);
        requireAdmin(actorId);
        ShopEntity shop = shops.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        requireVendorShop(shop);

        List<ShopApplicationEntity> attempts = applications.findByShopIdOrderByAttemptNumberDesc(shopId);
        Collection<UUID> userIds = new ArrayList<>();
        attempts.stream().map(ShopApplicationEntity::getApplicantUserId).filter(Objects::nonNull).forEach(userIds::add);
        attempts.stream().map(ShopApplicationEntity::getReviewerUserId).filter(Objects::nonNull).forEach(userIds::add);
        Map<UUID, UserEntity> people = new HashMap<>();
        users.findAllById(userIds).forEach(user -> people.put(user.getId(), user));

        List<ShopApplicationAttemptView> views = attempts.stream().map(attempt -> {
            UserEntity reviewer = people.get(attempt.getReviewerUserId());
            return new ShopApplicationAttemptView(attempt.getId(), attempt.getAttemptNumber(), attempt.getStatus(),
                    attempt.getSubmittedAt(), attempt.getReviewedAt(), attempt.getReviewReason(),
                    reviewer == null ? null : reviewer.getDisplayName(), readSnapshot(attempt));
        }).toList();
        ShopApplicationEntity mostRecent = attempts.isEmpty() ? null : attempts.get(0);
        UserEntity owner = mostRecent == null ? null : people.get(mostRecent.getApplicantUserId());
        ShopMembershipEntity ownerMembership = owner == null ? null
                : memberships.findByShopIdAndUserId(shopId, owner.getId()).orElse(null);
        return new ShopAdminDetailView(shop,
                owner == null ? null : owner.getDisplayName(), owner == null ? null : owner.getEmail(),
                ownerMembership == null ? null : ownerMembership.getStatus(),
                ownerMembership == null ? null : ownerMembership.getRevokedAt(),
                ownerMembership == null ? null : ownerMembership.getRevocationReason(),
                views, history.findByShopIdOrderByOccurredAtDescIdDesc(shopId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(UUID shopId, UUID applicationId, UUID actorId) {
        review(shopId, applicationId, actorId, ShopApplicationStatus.APPROVED, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(UUID shopId, UUID applicationId, UUID actorId, String reason) {
        review(shopId, applicationId, actorId, ShopApplicationStatus.REJECTED, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopEntity suspend(UUID shopId, UUID actorId, String reason) {
        requireFeature(Feature.VENDOR_MANAGEMENT);
        requireAdmin(actorId);
        ShopEntity shop = lockedVendorShop(shopId);
        if (shop.getStatus() != ShopStatus.ACTIVE) {
            throw new BusinessConflictException("Chỉ cửa hàng đang hoạt động mới có thể tạm ngưng.");
        }
        String normalizedReason = requiredReason(reason);
        ShopApplicationEntity latest = applications.findTopByShopIdOrderByAttemptNumberDesc(shopId).orElse(null);
        shop.setStatus(ShopStatus.SUSPENDED);
        shops.save(shop);
        history.save(new ShopStatusHistoryEntity(shopId, ShopStatus.ACTIVE, ShopStatus.SUSPENDED,
                actorId, latest == null ? null : latest.getId(), normalizedReason));
        return shop;
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopEntity reactivate(UUID shopId, UUID actorId, String reason) {
        requireFeature(Feature.VENDOR_MANAGEMENT);
        requireAdmin(actorId);
        if (!shops.existsById(shopId)) throw new ResourceNotFoundException("Không tìm thấy cửa hàng.");
        ShopMembershipEntity ownerCandidate = memberships.findByShopIdAndRoleAndStatus(
                        shopId, ShopMembershipRole.OWNER, ShopMembershipStatus.ACTIVE)
                .orElse(null);
        if (ownerCandidate == null) throw ownerUnavailable();
        // Lock the account before the shop to match submit's user-then-shop order. Membership is rechecked
        // under the shop lock below, so concurrent revocation cannot authorize stale owner data.
        UserEntity ownerUser = lockEligibleOwnerAccount(ownerCandidate.getUserId());

        ShopEntity shop = lockedVendorShop(shopId);
        if (shop.getStatus() != ShopStatus.SUSPENDED) {
            throw new BusinessConflictException("Chỉ cửa hàng đang tạm ngưng mới có thể khôi phục.");
        }
        requireEligibleOwner(shopId, ownerCandidate.getUserId(), ownerUser);
        String normalizedReason = optional(reason, 1000);
        ShopApplicationEntity latest = applications.findTopByShopIdOrderByAttemptNumberDesc(shopId).orElse(null);
        shop.setStatus(ShopStatus.ACTIVE);
        shops.save(shop);
        history.save(new ShopStatusHistoryEntity(shopId, ShopStatus.SUSPENDED, ShopStatus.ACTIVE,
                actorId, latest == null ? null : latest.getId(), normalizedReason));
        return shop;
    }

    /**
     * Revokes the single OWNER membership under the established shop-then-membership lock order.
     * An active shop is suspended in the same transaction so it cannot keep accepting sales
     * without an authorized owner. API sessions are revoked transactionally; browser sessions
     * are expired only after the membership change commits. Missing email falls back to user-id-only
     * browser-session cleanup, and missing account data does not block resolving the membership.
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeOwner(UUID shopId, UUID actorId, String rawReason) {
        requireFeature(Feature.VENDOR_MANAGEMENT);
        requireAdmin(actorId);
        String reason = requiredRevocationReason(rawReason);
        ShopEntity shop = lockedVendorShop(shopId);
        ShopMembershipEntity owner = memberships.findByShopIdAndRoleAndStatusForUpdate(
                        shopId, ShopMembershipRole.OWNER, ShopMembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessConflictException("Cửa hàng không có chủ sở hữu đang hoạt động."));
        UserEntity ownerUser = users.findByIdIncludingDeleted(owner.getUserId()).orElse(null);

        Instant now = Instant.now();
        owner.setStatus(ShopMembershipStatus.REVOKED);
        owner.setRevokedAt(now);
        owner.setRevokedByUserId(actorId);
        owner.setRevocationReason(reason);
        memberships.save(owner);

        if (shop.getStatus() == ShopStatus.ACTIVE) {
            shop.setStatus(ShopStatus.SUSPENDED);
            shops.save(shop);
            history.save(new ShopStatusHistoryEntity(shopId, ShopStatus.ACTIVE, ShopStatus.SUSPENDED,
                    actorId, null, "Thu hồi quyền chủ sở hữu: " + reason));
        }

        sessions.revokeAllForUser(owner.getUserId(), "SHOP_MEMBERSHIP_REVOKED");
        String ownerEmail = ownerUser == null ? null : sanitizer.trimToNull(ownerUser.getEmail(), 320);
        if (ownerEmail == null) webSessions.revokeAllAfterCommit(owner.getUserId());
        else webSessions.revokeAllAfterCommit(owner.getUserId(), ownerEmail);
    }

    private ShopEntity createInitialApplication(UserEntity user, ShopApplicationSnapshot snapshot) {
        UUID identitySeed = UUID.randomUUID();
        String compactId = identitySeed.toString().replace("-", "");
        String slugBase = slugBase(snapshot.shopName());
        if (slugBase.length() > 87) slugBase = slugBase.substring(0, 87);
        ShopEntity shop = new ShopEntity("VND-" + compactId, slugBase + "-" + compactId,
                snapshot.shopName(), ShopKind.VENDOR, ShopStatus.PENDING);
        shop = shops.saveAndFlush(shop);
        if (shop.getId() == null) throw new IllegalStateException("Shop persistence did not return an identifier");

        memberships.saveAndFlush(new ShopMembershipEntity(shop.getId(), user.getId(), ShopMembershipRole.OWNER));
        ShopApplicationEntity attempt = saveSubmittedAttempt(shop, user.getId(), 1, snapshot);
        history.save(new ShopStatusHistoryEntity(shop.getId(), null, ShopStatus.PENDING,
                user.getId(), attempt.getId(), null));
        return shop;
    }

    private ShopApplicationEntity saveSubmittedAttempt(ShopEntity shop, UUID applicantId, int attemptNumber,
                                                       ShopApplicationSnapshot snapshot) {
        ShopApplicationEntity attempt = new ShopApplicationEntity(shop.getId(), applicantId, attemptNumber,
                serializeSnapshot(snapshot));
        attempt.setStatus(ShopApplicationStatus.SUBMITTED);
        return applications.saveAndFlush(attempt);
    }

    private void review(UUID expectedShopId, UUID applicationId, UUID actorId,
                        ShopApplicationStatus target, String rawReason) {
        requireFeature(Feature.VENDOR_MANAGEMENT);
        requireAdmin(actorId);
        String reason = target == ShopApplicationStatus.REJECTED ? requiredReason(rawReason) : null;
        ShopApplicationEntity application = applications.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ đăng ký."));
        if (!expectedShopId.equals(application.getShopId())) {
            throw new ResourceNotFoundException("Không tìm thấy hồ sơ đăng ký trong cửa hàng này.");
        }
        UUID applicantUserId = application.getApplicantUserId();
        // Approval serializes against account disable/delete before taking the shop lock. The owner membership
        // and application are still revalidated under their locks before changing status.
        UserEntity approvedOwner = target == ShopApplicationStatus.APPROVED
                ? lockEligibleOwnerAccount(applicantUserId) : null;
        ShopEntity shop = lockedVendorShop(application.getShopId());
        // Lock order is shop, then application. Refresh prevents a second reviewer from using a stale
        // SUBMITTED entity after waiting for the shop lock.
        entityManager.refresh(application, LockModeType.PESSIMISTIC_WRITE);
        if (!shop.getId().equals(application.getShopId()) || application.getStatus() != ShopApplicationStatus.SUBMITTED
                || shop.getStatus() != ShopStatus.PENDING) {
            throw new BusinessConflictException("Hồ sơ đã được xử lý hoặc cửa hàng không còn chờ duyệt.");
        }
        if (target == ShopApplicationStatus.APPROVED) {
            if (!Objects.equals(applicantUserId, application.getApplicantUserId())) {
                throw ownerUnavailable();
            }
            requireEligibleOwner(shop.getId(), application.getApplicantUserId(), approvedOwner);
        }

        ShopStatus previousStatus = shop.getStatus();
        Instant now = Instant.now();
        application.setStatus(target);
        application.setReviewerUserId(actorId);
        application.setReviewedAt(now);
        application.setReviewReason(reason);
        applications.save(application);

        ShopStatus targetStatus = target == ShopApplicationStatus.APPROVED ? ShopStatus.ACTIVE : ShopStatus.REJECTED;
        shop.setStatus(targetStatus);
        shops.save(shop);
        history.save(new ShopStatusHistoryEntity(shop.getId(), previousStatus, targetStatus,
                actorId, application.getId(), reason));
        if (target == ShopApplicationStatus.APPROVED && notifications != null) {
            notifications.notifyShopApproved(applicantUserId, shop.getId());
        }
    }

    private ShopEntity lockedVendorShop(UUID shopId) {
        ShopEntity shop = shops.findByIdForUpdate(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        requireVendorShop(shop);
        return shop;
    }

    private UserEntity lockEligibleOwnerAccount(UUID ownerUserId) {
        if (ownerUserId == null) throw ownerUnavailable();
        UserEntity ownerUser = users.findByIdIncludingDeletedForUpdate(ownerUserId).orElse(null);
        if (!isEligibleOwnerAccount(ownerUser)) throw ownerUnavailable();
        return ownerUser;
    }

    private void requireEligibleOwner(UUID shopId, UUID expectedOwnerId, UserEntity ownerUser) {
        ShopMembershipEntity owner = memberships.findByShopIdAndRoleAndStatusForUpdate(
                        shopId, ShopMembershipRole.OWNER, ShopMembershipStatus.ACTIVE)
                .orElse(null);
        if (owner == null || !shopId.equals(owner.getShopId())
                || owner.getRole() != ShopMembershipRole.OWNER
                || owner.getStatus() != ShopMembershipStatus.ACTIVE || owner.getRevokedAt() != null
                || !Objects.equals(expectedOwnerId, owner.getUserId())
                || !Objects.equals(expectedOwnerId, ownerUser == null ? null : ownerUser.getId())
                || !isEligibleOwnerAccount(ownerUser)) {
            throw ownerUnavailable();
        }
    }

    private static boolean isEligibleOwnerAccount(UserEntity ownerUser) {
        return ownerUser != null && ownerUser.getDeletedAt() == null && ownerUser.getStatus() == UserStatus.ACTIVE
                && (ownerUser.getRole() == UserRole.USER || ownerUser.getRole() == UserRole.CUSTOMER
                || ownerUser.getRole() == UserRole.VENDOR)
                && ownerUser.isEmailVerified();
    }

    private boolean canOpenVendorWorkspace(UserEntity user, ShopEntity shop, ShopMembershipEntity owner) {
        return features.isEnabled(Feature.VENDOR_MANAGEMENT)
                && shop != null && shop.getStatus() == ShopStatus.ACTIVE
                && owner != null
                && owner.getRole() == ShopMembershipRole.OWNER
                && owner.getStatus() == ShopMembershipStatus.ACTIVE
                && owner.getRevokedAt() == null
                && Objects.equals(owner.getUserId(), user.getId())
                && isEligibleOwnerAccount(user);
    }

    private static BusinessConflictException ownerUnavailable() {
        return new BusinessConflictException("Cửa hàng không có chủ sở hữu đang hoạt động và đủ điều kiện.");
    }

    private void requireAdmin(UUID actorId) {
        UserEntity actor = users.findById(actorId)
                .orElseThrow(() -> new AccessDeniedException("Tài khoản không có quyền xét duyệt cửa hàng."));
        if ((actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN)
                || actor.getStatus() != UserStatus.ACTIVE || actor.getDeletedAt() != null) {
            throw new AccessDeniedException("Tài khoản không có quyền xét duyệt cửa hàng.");
        }
    }

    private UserEntity requireCustomer(UUID userId) {
        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        return requireCustomer(user);
    }

    private UserEntity requireCustomer(UserEntity user) {
        if ((user.getRole() != UserRole.USER && user.getRole() != UserRole.CUSTOMER)
                || user.getStatus() != UserStatus.ACTIVE || user.getDeletedAt() != null
                || !user.isEmailVerified()) {
            throw new AccessDeniedException("Chỉ tài khoản khách đã xác thực email mới được đăng ký nhà bán.");
        }
        return user;
    }

    private Optional<ShopMembershipEntity> activeOwner(UUID userId) {
        List<ShopMembershipEntity> owners = memberships.findByUserIdAndRoleAndStatus(
                userId, ShopMembershipRole.OWNER, ShopMembershipStatus.ACTIVE);
        if (owners.size() > 1) {
            throw new BusinessConflictException("Tài khoản có nhiều cửa hàng chủ sở hữu cần được quản trị viên kiểm tra.");
        }
        return owners.stream().findFirst();
    }

    private void requireVendorShop(ShopEntity shop) {
        if (shop == null || shop.getKind() != ShopKind.VENDOR) {
            throw new ResourceNotFoundException("Không tìm thấy cửa hàng nhà bán.");
        }
    }

    private ShopApplicationSnapshot normalizeAndSnapshot(ShopApplicationForm form) {
        String businessType = required(form.getBusinessType(), 20, "Vui lòng chọn loại hình kinh doanh.")
                .toUpperCase(Locale.ROOT);
        if (!List.of("INDIVIDUAL", "HOUSEHOLD", "COMPANY").contains(businessType)) {
            throw new BusinessConflictException("Loại hình kinh doanh không hợp lệ.");
        }
        String contactEmail = required(form.getContactEmail(), 320, "Email liên hệ là bắt buộc.")
                .toLowerCase(Locale.ROOT);
        if (!contactEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessConflictException("Email liên hệ không hợp lệ.");
        }
        String phone = required(form.getContactPhone(), 40, "Số điện thoại liên hệ là bắt buộc.");
        if (!phone.matches("^[0-9+() .-]{8,40}$")) {
            throw new BusinessConflictException("Số điện thoại liên hệ không hợp lệ.");
        }
        return new ShopApplicationSnapshot(
                required(form.getShopName(), 160, "Tên cửa hàng là bắt buộc."),
                businessType,
                required(form.getLegalName(), 200, "Tên pháp lý / chủ hộ là bắt buộc."),
                optional(form.getTaxCode(), 60),
                optional(form.getRegistrationNumber(), 80),
                contactEmail,
                phone,
                required(form.getBusinessAddress(), 500, "Địa chỉ kinh doanh là bắt buộc."),
                optional(form.getDescription(), 2000));
    }

    private String serializeSnapshot(ShopApplicationSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize shop application snapshot", exception);
        }
    }

    private ShopApplicationSnapshot readSnapshot(ShopApplicationEntity application) {
        try {
            return objectMapper.readValue(application.getProfileSnapshot(), ShopApplicationSnapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessConflictException("Không thể đọc snapshot hồ sơ đăng ký.");
        }
    }

    private ShopApplicationForm toForm(ShopApplicationSnapshot snapshot) {
        ShopApplicationForm form = new ShopApplicationForm();
        form.setShopName(snapshot.shopName());
        form.setBusinessType(snapshot.businessType());
        form.setLegalName(snapshot.legalName());
        form.setTaxCode(snapshot.taxCode());
        form.setRegistrationNumber(snapshot.registrationNumber());
        form.setContactEmail(snapshot.contactEmail());
        form.setContactPhone(snapshot.contactPhone());
        form.setBusinessAddress(snapshot.businessAddress());
        form.setDescription(snapshot.description());
        return form;
    }

    private String requiredReason(String raw) {
        String reason = required(raw, 1000, "Lý do là bắt buộc.");
        if (reason.length() < 10) {
            throw new BusinessConflictException("Lý do từ chối hoặc tạm ngưng cần ít nhất 10 ký tự.");
        }
        return reason;
    }

    private String requiredRevocationReason(String raw) {
        String reason = required(raw, 1000, "Vui lòng nhập lý do thu hồi quyền chủ sở hữu.");
        if (reason.length() < 10) {
            throw new BusinessConflictException("Lý do thu hồi cần ít nhất 10 ký tự.");
        }
        return reason;
    }

    private String required(String raw, int maxLength, String message) {
        String normalized = optional(raw, maxLength);
        if (normalized == null) throw new BusinessConflictException(message);
        return normalized;
    }

    private String optional(String raw, int maxLength) {
        return sanitizer.trimToNull(raw, maxLength);
    }

    private String submissionMessage(ShopStatus status) {
        return switch (status) {
            case PENDING -> "Hồ sơ của bạn đang chờ quản trị viên xét duyệt.";
            case ACTIVE -> "Cửa hàng của bạn đã được kích hoạt.";
            case SUSPENDED -> "Cửa hàng đang tạm ngưng; vui lòng liên hệ hỗ trợ.";
            case CLOSED -> "Cửa hàng đã đóng và không thể gửi lại hồ sơ.";
            case REJECTED -> APPLICATION_BLOCKED;
        };
    }

    private String slugBase(String shopName) {
        String normalized = Normalizer.normalize(shopName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "shop" : normalized;
    }

    private void requireFeature(Feature feature) {
        if (features == null || !features.isEnabled(feature)) {
            throw new AccessDeniedException("Tính năng cửa hàng chưa được bật.");
        }
    }
}
