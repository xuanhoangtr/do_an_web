package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.ShopMembershipRole;
import com.utewedding.app.domain.ShopMembershipStatus;
import com.utewedding.app.domain.ShopPayoutDestinationStatus;
import com.utewedding.app.domain.ShopPayoutOperationType;
import com.utewedding.app.domain.ShopPayoutStatus;
import com.utewedding.app.domain.ShopSettlementPeriodStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.domain.entity.ShopFinanceStateEntity;
import com.utewedding.app.domain.entity.ShopMembershipEntity;
import com.utewedding.app.domain.entity.ShopPayoutDestinationEntity;
import com.utewedding.app.domain.entity.ShopPayoutEntity;
import com.utewedding.app.domain.entity.ShopPayoutOperationEntity;
import com.utewedding.app.domain.entity.ShopSettlementPeriodEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.ShopPayoutDestinationCommand;
import com.utewedding.app.dto.request.ShopPayoutMarkPaidCommand;
import com.utewedding.app.dto.request.ShopPayoutRequestCommand;
import com.utewedding.app.dto.request.ShopPayoutVerifyDestinationCommand;
import com.utewedding.app.dto.response.ShopPayoutBankInstructions;
import com.utewedding.app.dto.response.ShopPayoutDestinationView;
import com.utewedding.app.dto.response.ShopPayoutView;
import com.utewedding.app.dto.response.ShopOwnerPayoutView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.ManagedMediaObjectRepository;
import com.utewedding.app.repository.ShopFinanceStateRepository;
import com.utewedding.app.repository.ShopMembershipRepository;
import com.utewedding.app.repository.ShopPayoutDestinationRepository;
import com.utewedding.app.repository.ShopPayoutOperationRepository;
import com.utewedding.app.repository.ShopPayoutRepository;
import com.utewedding.app.repository.ShopSettlementEligibilityRepository;
import com.utewedding.app.repository.ShopSettlementPeriodRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Versioned payout destinations and auditable, manual shop-to-bank payout workflow. */
@Service
public class ShopPayoutService {
    private static final Pattern BANK_CODE = Pattern.compile("[A-Z0-9._-]{2,40}");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("[0-9]{6,34}");
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[\\p{L} .'-]{2,120}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,190}");
    private static final Pattern PROVIDER_REFERENCE = Pattern.compile("[A-Za-z0-9._/-]{4,190}");
    private static final List<ShopPayoutStatus> ACTIVE_PERIOD_STATUSES = List.of(
            ShopPayoutStatus.REQUESTED, ShopPayoutStatus.APPROVED, ShopPayoutStatus.PROCESSING,
            ShopPayoutStatus.PAID);
    private static final List<ShopPayoutStatus> ADMIN_QUEUE_STATUSES = List.of(
            ShopPayoutStatus.REQUESTED, ShopPayoutStatus.APPROVED, ShopPayoutStatus.PROCESSING,
            ShopPayoutStatus.PAID, ShopPayoutStatus.CANCELLED);

    private final ShopPayoutDestinationRepository destinations;
    private final ShopPayoutRepository payouts;
    private final ShopPayoutOperationRepository operations;
    private final ShopMembershipRepository memberships;
    private final ShopAccessPolicy access;
    private final ShopFinanceStateRepository financeStates;
    private final ShopSettlementPeriodRepository periods;
    private final ShopSettlementEligibilityRepository eligibility;
    private final ShopSettlementPeriodService periodService;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ManagedMediaObjectRepository mediaObjects;
    private final ManagedMediaService mediaService;
    private final CloudinaryPrivateMediaProxy privateMediaProxy;
    private final AuditLogPort audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @PersistenceContext private EntityManager entityManager;

    public ShopPayoutService(ShopPayoutDestinationRepository destinations, ShopPayoutRepository payouts,
                             ShopPayoutOperationRepository operations,
                             ShopMembershipRepository memberships, ShopAccessPolicy access,
                             ShopFinanceStateRepository financeStates, ShopSettlementPeriodRepository periods,
                             ShopSettlementEligibilityRepository eligibility,
                             ShopSettlementPeriodService periodService, UserRepository users,
                             PasswordEncoder passwordEncoder, ManagedMediaObjectRepository mediaObjects,
                             ManagedMediaService mediaService, CloudinaryPrivateMediaProxy privateMediaProxy,
                             AuditLogPort audit, JdbcTemplate jdbc,
                             Clock shopSettlementClock) {
        this.destinations = destinations;
        this.payouts = payouts;
        this.operations = operations;
        this.memberships = memberships;
        this.access = access;
        this.financeStates = financeStates;
        this.periods = periods;
        this.eligibility = eligibility;
        this.periodService = periodService;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mediaObjects = mediaObjects;
        this.mediaService = mediaService;
        this.privateMediaProxy = privateMediaProxy;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = shopSettlementClock;
    }

    @Transactional(readOnly = true)
    public List<ShopPayoutDestinationView> ownerDestinations(UserPrincipal owner) {
        UUID shopId = ownerShop(owner, false);
        return destinations.findByShopIdOrderByVersionDesc(shopId).stream()
                .map(ShopPayoutDestinationView::from).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutDestinationView submitDestination(UserPrincipal owner, String idempotencyKey,
                                                       ShopPayoutDestinationCommand command,
                                                       RequestContext context) {
        UUID shopId = ownerShop(owner, true);
        String bankCode = normalizeBankCode(command == null ? null : command.bankCode());
        String accountNumber = normalizeAccountNumber(command == null ? null : command.accountNumber());
        String accountName = normalizeAccountName(command == null ? null : command.accountHolderName());
        confirmOwnerPassword(owner.getId(), command == null ? null : command.passwordConfirmation());
        lockFinanceState(shopId);
        // Never persist a plain digest of bank details: account numbers are low-entropy
        // enough for offline guessing. Compare the encrypted destination after replay.
        String hash = requestHash("destination", shopId);
        ShopPayoutOperationEntity old = lockOperation(owner.getId(), idempotencyKey,
                ShopPayoutOperationType.SUBMIT_DESTINATION, hash);
        if (old != null) {
            if (old.getDestinationId() == null) throw idempotencyConflict();
            ShopPayoutDestinationEntity existing = destinations.findByIdAndShopId(old.getDestinationId(), shopId)
                    .orElseThrow(ShopPayoutService::idempotencyConflict);
            if (!existing.getBankCode().equals(bankCode)
                    || !existing.getAccountNumber().equals(accountNumber)
                    || !existing.getAccountHolderName().equals(accountName)) {
                throw idempotencyConflict();
            }
            return ShopPayoutDestinationView.from(existing);
        }

        long version = Math.addExact(destinations.maxVersion(shopId), 1);
        Instant now = clock.instant();
        ShopPayoutDestinationEntity destination = destinations.saveAndFlush(new ShopPayoutDestinationEntity(
                shopId, version, bankCode, accountNumber, accountName, owner.getId(), now));
        saveOperation(owner.getId(), ShopPayoutOperationType.SUBMIT_DESTINATION, idempotencyKey, hash,
                destination.getId(), null, now);
        audit.record("PAYOUT_DESTINATION_CHANGED", owner.getId(), "ShopPayoutDestination", destination.getId(),
                Map.of("shopId", shopId, "destinationVersion", version, "status", destination.getStatus().name()),
                context);
        return ShopPayoutDestinationView.from(destination);
    }

    @Transactional(readOnly = true)
    public List<ShopOwnerPayoutView> ownerPayouts(UserPrincipal owner) {
        UUID shopId = ownerShop(owner, false);
        return payouts.findTop100ByShopIdOrderByCreatedAtDesc(shopId).stream()
                .map(ShopOwnerPayoutView::from).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopOwnerPayoutView requestPayout(UserPrincipal owner, UUID periodId, String idempotencyKey,
                                            ShopPayoutRequestCommand command, RequestContext context) {
        UUID shopId = ownerShop(owner, true);
        confirmOwnerPassword(owner.getId(), command == null ? null : command.passwordConfirmation());
        lockFinanceState(shopId);
        ShopSettlementPeriodEntity observed = periods.findById(periodId).orElseThrow(ShopPayoutService::missingPeriod);
        if (!shopId.equals(observed.getShopId())) throw deniedOwner();
        ShopSettlementPeriodEntity period = periods.findByIdForUpdate(periodId).orElseThrow(ShopPayoutService::missingPeriod);
        String hash = requestHash("request-payout", periodId, shopId);
        ShopPayoutOperationEntity old = lockOperation(owner.getId(), idempotencyKey,
                ShopPayoutOperationType.REQUEST_PAYOUT, hash);
        if (old != null) {
            if (old.getPayoutId() == null) throw idempotencyConflict();
            return ShopOwnerPayoutView.from(payouts.findByIdAndShopId(old.getPayoutId(), shopId)
                    .orElseThrow(ShopPayoutService::idempotencyConflict));
        }
        if (period.getStatus() != ShopSettlementPeriodStatus.APPROVED) {
            throw new BusinessConflictException("Chỉ kỳ settlement đã được Admin duyệt mới có thể yêu cầu chi.");
        }
        assertEligible(period);
        if (period.getNetAmount() <= 0) {
            throw new BusinessConflictException("Kỳ settlement không có số dư dương để yêu cầu payout.");
        }
        if (payouts.findBySettlementPeriodIdAndStatusIn(periodId, ACTIVE_PERIOD_STATUSES).isPresent()) {
            throw new BusinessConflictException("Kỳ settlement đã có yêu cầu payout hoặc đã được chi.");
        }
        ShopPayoutDestinationEntity destination = destinations
                .findFirstByShopIdAndStatusOrderByVersionDesc(shopId, ShopPayoutDestinationStatus.VERIFIED)
                .orElseThrow(() -> new BusinessConflictException("Cửa hàng chưa có tài khoản nhận tiền đã xác minh."));
        Instant now = clock.instant();
        ShopPayoutEntity payout = payouts.saveAndFlush(new ShopPayoutEntity(shopId, periodId, destination,
                period.getNetAmount(), owner.getId(), now));
        saveOperation(owner.getId(), ShopPayoutOperationType.REQUEST_PAYOUT, idempotencyKey, hash,
                null, payout.getId(), now);
        audit.record("PAYOUT_STATUS_CHANGED", owner.getId(), "ShopPayout", payout.getId(),
                Map.of("shopId", shopId, "status", payout.getStatus().name(), "amount", payout.getAmount()), context);
        return ShopOwnerPayoutView.from(payout);
    }

    @Transactional(readOnly = true)
    public List<ShopPayoutDestinationView> adminPendingDestinations(UUID actorId) {
        requireFinanceAdmin(actorId);
        return destinations.findTop200ByStatusOrderByCreatedAtAscId(ShopPayoutDestinationStatus.PENDING_VERIFICATION)
                .stream().map(ShopPayoutDestinationView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ShopPayoutView> adminPayouts(UUID actorId) {
        requireFinanceAdmin(actorId);
        return payouts.findTop200ByStatusInOrderByCreatedAtAsc(ADMIN_QUEUE_STATUSES).stream()
                .map(ShopPayoutView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ShopPayoutView> adminProcessingPayouts(UUID actorId) {
        requireFinanceAdmin(actorId);
        return payouts.findTop200ByStatusInOrderByCreatedAtAsc(List.of(ShopPayoutStatus.PROCESSING)).stream()
                .map(ShopPayoutView::from).toList();
    }

    @Transactional(readOnly = true)
    public ShopPayoutView adminPayout(UUID actorId, UUID payoutId) {
        requireFinanceAdmin(actorId);
        return ShopPayoutView.from(payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout));
    }

    @Transactional(readOnly = true)
    public ShopPayoutView adminProcessingPayout(UUID actorId, UUID payoutId) {
        requireFinanceAdmin(actorId);
        ShopPayoutEntity payout = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        if (payout.getStatus() != ShopPayoutStatus.PROCESSING) throw missingPayout();
        return ShopPayoutView.from(payout);
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutDestinationView verifyDestination(UUID actorId, UUID destinationId, String idempotencyKey,
            ShopPayoutVerifyDestinationCommand command, RequestContext context) {
        requireFinanceAdmin(actorId);
        String reference = normalizeVerificationReference(command == null ? null : command.evidenceReference());
        String note = limitNote(command == null ? null : command.note());
        ShopPayoutDestinationEntity observed = destinations.findById(destinationId)
                .orElseThrow(ShopPayoutService::missingDestination);
        lockFinanceState(observed.getShopId());
        String hash = requestHash("verify-destination", destinationId, reference, note);
        ShopPayoutOperationEntity old = lockOperation(actorId, idempotencyKey,
                ShopPayoutOperationType.VERIFY_DESTINATION, hash);
        if (old != null) {
            if (!destinationId.equals(old.getDestinationId())) throw idempotencyConflict();
            return ShopPayoutDestinationView.from(destinations.findById(destinationId)
                    .orElseThrow(ShopPayoutService::missingDestination));
        }
        ShopPayoutDestinationEntity destination = destinations.findByIdForUpdate(destinationId)
                .orElseThrow(ShopPayoutService::missingDestination);
        if (destination.getStatus() != ShopPayoutDestinationStatus.PENDING_VERIFICATION) {
            throw new BusinessConflictException("Chỉ tài khoản đang chờ mới có thể được xác minh.");
        }
        for (ShopPayoutDestinationEntity previous : destinations.findByShopIdAndStatusForUpdate(
                destination.getShopId(), ShopPayoutDestinationStatus.VERIFIED)) {
            previous.supersede();
            destinations.save(previous);
        }
        Instant now = clock.instant();
        destination.verify(actorId, reference, note, now);
        destinations.saveAndFlush(destination);
        saveOperation(actorId, ShopPayoutOperationType.VERIFY_DESTINATION, idempotencyKey, hash,
                destinationId, null, now);
        audit.record("PAYOUT_DESTINATION_CHANGED", actorId, "ShopPayoutDestination", destinationId,
                Map.of("shopId", destination.getShopId(), "destinationVersion", destination.getVersion(),
                        "status", destination.getStatus().name(), "verificationReference", reference), context);
        return ShopPayoutDestinationView.from(destination);
    }

    /** Explicitly audited reveal; default list/detail DTOs never contain the full bank account. */
    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutBankInstructions revealDestination(UUID actorId, UUID destinationId, RequestContext context) {
        requireFinanceAdmin(actorId);
        ShopPayoutDestinationEntity destination = destinations.findById(destinationId)
                .orElseThrow(ShopPayoutService::missingDestination);
        audit.record("PAYOUT_DESTINATION_VIEWED", actorId, "ShopPayoutDestination", destinationId,
                Map.of("shopId", destination.getShopId(), "destinationVersion", destination.getVersion()), context);
        return new ShopPayoutBankInstructions(destination.getId(), destination.getVersion(), destination.getBankCode(),
                destination.getAccountNumber(), destination.getAccountHolderName());
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutView approve(UUID actorId, UUID payoutId, String idempotencyKey, RequestContext context) {
        requireFinanceAdmin(actorId);
        ShopPayoutEntity observed = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        lockFinanceState(observed.getShopId());
        periods.findByIdForUpdate(observed.getSettlementPeriodId()).orElseThrow(ShopPayoutService::missingPeriod);
        String hash = requestHash("approve-payout", payoutId);
        ShopPayoutOperationEntity old = lockOperation(actorId, idempotencyKey,
                ShopPayoutOperationType.APPROVE_PAYOUT, hash);
        if (old != null) return replayPayout(old, payoutId);
        ShopPayoutEntity payout = payouts.findByIdForUpdate(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        ShopSettlementPeriodEntity period = periods.findByIdForUpdate(payout.getSettlementPeriodId())
                .orElseThrow(ShopPayoutService::missingPeriod);
        requireSameShop(period, payout);
        requireStatus(payout, ShopPayoutStatus.REQUESTED, "Chỉ payout đang chờ mới được duyệt.");
        requireStatus(period, ShopSettlementPeriodStatus.APPROVED, "Kỳ settlement không còn ở trạng thái đã duyệt.");
        assertEligible(period);
        requireAmountMatchesPeriod(payout, period);
        Instant now = clock.instant();
        payout.approve(actorId, now);
        payouts.saveAndFlush(payout);
        saveOperation(actorId, ShopPayoutOperationType.APPROVE_PAYOUT, idempotencyKey, hash,
                null, payoutId, now);
        audit.record("PAYOUT_STATUS_CHANGED", actorId, "ShopPayout", payoutId,
                Map.of("shopId", payout.getShopId(), "status", payout.getStatus().name()), context);
        return ShopPayoutView.from(payout);
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutView beginProcessing(UUID actorId, UUID payoutId, String idempotencyKey,
                                          RequestContext context) {
        requireFinanceAdmin(actorId);
        ShopPayoutEntity observed = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        lockFinanceState(observed.getShopId());
        ShopSettlementPeriodEntity period = periods.findByIdForUpdate(observed.getSettlementPeriodId())
                .orElseThrow(ShopPayoutService::missingPeriod);
        String hash = requestHash("process-payout", payoutId);
        ShopPayoutOperationEntity old = lockOperation(actorId, idempotencyKey,
                ShopPayoutOperationType.PROCESS_PAYOUT, hash);
        if (old != null) return replayPayout(old, payoutId);
        ShopPayoutEntity payout = payouts.findByIdForUpdate(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        period = periods.findByIdForUpdate(payout.getSettlementPeriodId()).orElseThrow(ShopPayoutService::missingPeriod);
        requireSameShop(period, payout);
        requireStatus(payout, ShopPayoutStatus.APPROVED, "Chỉ payout đã duyệt mới có thể chuyển xử lý.");
        requireStatus(period, ShopSettlementPeriodStatus.APPROVED,
                "Kỳ settlement đã thay đổi; payout cần được đối chiếu lại trước khi chuyển khoản.");
        if (Objects.equals(payout.getApprovedBy(), actorId)) {
            throw new AccessDeniedException("Người duyệt payout không được tự kiểm tra và chuyển khoản.");
        }
        requireAmountMatchesPeriod(payout, period);
        // Settlement owns the final eligibility check and the special post-approval
        // refund recalculation. Do not pre-check here: it would reject that refund
        // before the period can be reset to DRAFT and the payout cancelled with audit.
        Instant now = clock.instant();
        ShopSettlementPeriodEntity transitioned = periodService.beginProcessing(period.getId(), actorId);
        if (transitioned.getStatus() == ShopSettlementPeriodStatus.DRAFT) {
            payout.cancel("Kỳ settlement có thay đổi mới; Admin cần duyệt lại trước khi tạo payout khác.");
            payouts.saveAndFlush(payout);
            saveOperation(actorId, ShopPayoutOperationType.PROCESS_PAYOUT, idempotencyKey, hash,
                    null, payoutId, now);
            audit.record("PAYOUT_STATUS_CHANGED", actorId, "ShopPayout", payoutId,
                    Map.of("shopId", payout.getShopId(), "status", payout.getStatus().name(),
                            "blockedReason", "SETTLEMENT_RECALCULATED"), context);
            return ShopPayoutView.from(payout);
        }
        if (transitioned.getStatus() != ShopSettlementPeriodStatus.PROCESSING) {
            throw new BusinessConflictException("Kỳ settlement đã thay đổi; chưa thể chuyển khoản.");
        }
        requireAmountMatchesPeriod(payout, transitioned);
        payout.beginProcessing(actorId, now);
        payouts.saveAndFlush(payout);
        saveOperation(actorId, ShopPayoutOperationType.PROCESS_PAYOUT, idempotencyKey, hash,
                null, payoutId, now);
        audit.record("PAYOUT_STATUS_CHANGED", actorId, "ShopPayout", payoutId,
                Map.of("shopId", payout.getShopId(), "status", payout.getStatus().name()), context);
        return ShopPayoutView.from(payout);
    }

    /** Records an already completed manual transfer. It deliberately does not recheck holds. */
    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutView markPaid(UUID actorId, UUID payoutId, String idempotencyKey,
                                   ShopPayoutMarkPaidCommand command, RequestContext context) {
        requireFinanceAdmin(actorId);
        String reference = normalizeProviderReference(command == null ? null : command.providerReference());
        UUID proofId = command == null ? null : command.proofMediaId();
        if (proofId == null) throw new BusinessConflictException("Cần đính kèm chứng từ chuyển khoản.");
        String note = limitNote(command.note());
        ShopPayoutEntity observed = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        lockFinanceState(observed.getShopId());
        ShopSettlementPeriodEntity period = periods.findByIdForUpdate(observed.getSettlementPeriodId())
                .orElseThrow(ShopPayoutService::missingPeriod);
        String hash = requestHash("mark-paid", payoutId, reference, proofId, note);
        ShopPayoutOperationEntity old = lockOperation(actorId, idempotencyKey,
                ShopPayoutOperationType.MARK_PAYOUT_PAID, hash);
        if (old != null) return replayPayout(old, payoutId);
        ShopPayoutEntity payout = payouts.findByIdForUpdate(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        period = periods.findByIdForUpdate(payout.getSettlementPeriodId()).orElseThrow(ShopPayoutService::missingPeriod);
        requireSameShop(period, payout);
        requireStatus(payout, ShopPayoutStatus.PROCESSING, "Chỉ payout đang xử lý mới được xác nhận đã chuyển tiền.");
        requireStatus(period, ShopSettlementPeriodStatus.PROCESSING,
                "Kỳ settlement chưa ở trạng thái đang xử lý.");
        if (payout.getProofMediaId() != null && !payout.getProofMediaId().equals(proofId)) {
            throw new BusinessConflictException("Payout đã gắn chứng từ chuyển khoản khác.");
        }
        if (Objects.equals(payout.getApprovedBy(), actorId)) {
            throw new AccessDeniedException("Người duyệt payout không được tự xác nhận đã chuyển khoản.");
        }
        if (payouts.existsByProviderCodeAndProviderReference(payout.getProviderCode(), reference)) {
            throw new BusinessConflictException("Mã tham chiếu chuyển khoản đã được dùng cho payout khác.");
        }
        if (payout.getProofMediaId() == null && payouts.existsByProofMediaId(proofId)) {
            throw new BusinessConflictException("Chứng từ này đã được gắn với payout khác.");
        }
        ManagedMediaObjectEntity proof = mediaObjects.findById(proofId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy chứng từ chuyển khoản."));
        if (!actorId.equals(proof.getOwnerId()) || !"PAYOUT_PROOF".equals(proof.getPurpose())
                || proof.getStatus() != ManagedMediaStatus.READY || !"authenticated".equals(proof.getDeliveryType())
                || proof.isLegalHold()) {
            throw new BusinessConflictException("Chứng từ phải là tệp riêng tư mới tải lên bởi Admin đang xác nhận.");
        }
        proof.setLegalHold(true);
        proof.setLegalHoldReason("Payout " + payoutId + " đã được xác nhận chuyển khoản.");
        mediaObjects.saveAndFlush(proof);
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE shop_settlement_periods
                SET status='PAID', paid_by=?, paid_at=?, version=version+1, updated_at=?
                WHERE id=? AND shop_id=? AND status='PROCESSING' AND paid_at IS NULL
                """, actorId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                period.getId(), payout.getShopId());
        if (updated != 1) throw new BusinessConflictException("Không thể ghi nhận kỳ đã thanh toán; hãy đối chiếu lại.");
        payout.markPaid(actorId, reference, proofId, note, now);
        payouts.saveAndFlush(payout);
        saveOperation(actorId, ShopPayoutOperationType.MARK_PAYOUT_PAID, idempotencyKey, hash,
                null, payoutId, now);
        audit.record("PAYOUT_STATUS_CHANGED", actorId, "ShopPayout", payoutId,
                Map.of("shopId", payout.getShopId(), "status", payout.getStatus().name(),
                        "providerReference", reference, "proofMediaId", proofId,
                        "recordedAfterProcessing", true), context);
        return ShopPayoutView.from(payout);
    }

    public ManagedMediaObjectEntity uploadPayoutProof(UserPrincipal actor, MultipartFile file) {
        if (actor == null || actor.getId() == null) throw deniedAdmin();
        requireFinanceAdmin(actor.getId());
        return mediaService.upload(file, actor.getId(), "PAYOUT_PROOF", null);
    }

    /** Uploads and permanently attaches evidence to one existing PROCESSING payout. */
    public ManagedMediaObjectEntity uploadPayoutProofForProcessing(UserPrincipal actor, UUID payoutId,
                                                                    MultipartFile file) {
        if (actor == null || actor.getId() == null) throw deniedAdmin();
        requireFinanceAdmin(actor.getId());
        ShopPayoutEntity payout = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        if (payout.getStatus() != ShopPayoutStatus.PROCESSING) {
            throw new BusinessConflictException("Chỉ payout đang xử lý mới được đính kèm chứng từ chuyển khoản.");
        }
        if (Objects.equals(payout.getApprovedBy(), actor.getId())) {
            throw new AccessDeniedException("Người duyệt payout không được tự đính kèm chứng từ chuyển khoản.");
        }
        if (payout.getProofMediaId() != null) {
            throw new BusinessConflictException("Payout đã có chứng từ chuyển khoản.");
        }

        ManagedMediaObjectEntity media = mediaService.upload(file, actor.getId(), "PAYOUT_PROOF", null);
        try {
            if (payouts.attachProofToProcessingPayout(payoutId, media.getId()) != 1) {
                throw new BusinessConflictException(
                        "Payout không còn đang xử lý hoặc đã có chứng từ chuyển khoản.");
            }
            return media;
        } catch (RuntimeException error) {
            if (!payouts.existsByProofMediaId(media.getId())) {
                mediaService.abortUnattachedPayoutProofUpload(media.getId(), actor.getId());
            }
            throw error;
        }
    }

    @Transactional(readOnly = true)
    public PayoutProofDownload payoutProof(UUID actorId, UUID payoutId) {
        requireFinanceAdmin(actorId);
        ShopPayoutEntity payout = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        if (payout.getProofMediaId() == null || payout.getStatus() != ShopPayoutStatus.PAID) {
            throw new BusinessConflictException("Chứng từ chỉ hiển thị sau khi ghi nhận chuyển khoản.");
        }
        ManagedMediaObjectEntity proof = mediaObjects.findById(payout.getProofMediaId())
                .filter(item -> "PAYOUT_PROOF".equals(item.getPurpose())
                        && item.getStatus() == ManagedMediaStatus.READY
                        && "authenticated".equals(item.getDeliveryType())
                        && item.getShopId() == null && item.getApplicationId() == null)
                .orElseThrow(() -> new BusinessConflictException("Chứng từ chuyển khoản không khả dụng."));
        CloudinaryPrivateMediaProxy.OpenedDownload stream = privateMediaProxy.open(proof);
        String extension = "image/png".equals(proof.getDetectedMime()) ? "png" : "jpg";
        return new PayoutProofDownload(stream, "payout-proof-" + payout.getId() + "." + extension);
    }

    public record PayoutProofDownload(CloudinaryPrivateMediaProxy.OpenedDownload stream, String fileName) { }

    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutView cancel(UUID actorId, UUID payoutId, String idempotencyKey,
                                 String reason, RequestContext context) {
        requireFinanceAdmin(actorId);
        String note = limitNote(reason);
        ShopPayoutEntity observed = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        lockFinanceState(observed.getShopId());
        periods.findByIdForUpdate(observed.getSettlementPeriodId()).orElseThrow(ShopPayoutService::missingPeriod);
        String hash = requestHash("cancel-payout", payoutId, note);
        ShopPayoutOperationEntity old = lockOperation(actorId, idempotencyKey,
                ShopPayoutOperationType.CANCEL_PAYOUT, hash);
        if (old != null) return replayPayout(old, payoutId);
        ShopPayoutEntity payout = payouts.findByIdForUpdate(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        if (payout.getStatus() != ShopPayoutStatus.REQUESTED && payout.getStatus() != ShopPayoutStatus.APPROVED) {
            throw new BusinessConflictException("Payout không thể hủy sau khi Admin đã bắt đầu chuyển khoản.");
        }
        Instant now = clock.instant();
        payout.cancel(note);
        payouts.saveAndFlush(payout);
        saveOperation(actorId, ShopPayoutOperationType.CANCEL_PAYOUT, idempotencyKey, hash,
                null, payoutId, now);
        audit.record("PAYOUT_STATUS_CHANGED", actorId, "ShopPayout", payoutId,
                Map.of("shopId", payout.getShopId(), "status", payout.getStatus().name(), "reason", note), context);
        return ShopPayoutView.from(payout);
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopPayoutBankInstructions revealPayoutInstructions(UUID actorId, UUID payoutId,
                                                               RequestContext context) {
        requireFinanceAdmin(actorId);
        ShopPayoutEntity payout = payouts.findById(payoutId).orElseThrow(ShopPayoutService::missingPayout);
        audit.record("PAYOUT_DESTINATION_VIEWED", actorId, "ShopPayout", payoutId,
                Map.of("shopId", payout.getShopId(), "destinationId", payout.getDestinationId(),
                        "destinationVersion", payout.getDestinationVersion()), context);
        return new ShopPayoutBankInstructions(payout.getDestinationId(), payout.getDestinationVersion(),
                payout.getDestinationBankCodeSnapshot(), payout.getDestinationAccountNumberSnapshot(),
                payout.getDestinationAccountHolderSnapshot());
    }

    private UUID ownerShop(UserPrincipal actor, boolean mutation) {
        if (actor == null || actor.getId() == null || !actor.isEnabled()
                || (actor.getRole() != com.utewedding.app.security.AppRole.USER
                && actor.getRole() != com.utewedding.app.security.AppRole.CUSTOMER)) throw deniedOwner();
        List<ShopMembershipEntity> owners = memberships.findByUserIdAndRoleAndStatus(
                actor.getId(), ShopMembershipRole.OWNER, ShopMembershipStatus.ACTIVE);
        if (owners.size() != 1 || owners.get(0).getRevokedAt() != null) throw deniedOwner();
        UUID shopId = owners.get(0).getShopId();
        if (mutation) access.requireMutation(actor, shopId);
        else access.requireRead(actor, shopId);
        return shopId;
    }

    private void confirmOwnerPassword(UUID ownerId, String password) {
        if (password == null || password.isBlank() || password.length() > 256) {
            throw new BusinessConflictException("Vui lòng xác nhận mật khẩu để tiếp tục.");
        }
        UserEntity owner = users.findByIdForUpdate(ownerId).orElseThrow(ShopPayoutService::deniedOwner);
        if (owner.getStatus() != UserStatus.ACTIVE
                || (owner.getRole() != UserRole.USER && owner.getRole() != UserRole.CUSTOMER)
                || !passwordEncoder.matches(password, owner.getPasswordHash())) {
            throw new AccessDeniedException("Mật khẩu xác nhận không đúng hoặc tài khoản không hoạt động.");
        }
    }

    private void requireFinanceAdmin(UUID actorId) {
        UserEntity actor = users.findById(actorId).orElseThrow(ShopPayoutService::deniedAdmin);
        if (actor.getStatus() != UserStatus.ACTIVE
                || (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN)) {
            throw deniedAdmin();
        }
    }

    private void lockFinanceState(UUID shopId) {
        Optional<ShopFinanceStateEntity> state = financeStates.findByShopIdForUpdate(shopId);
        if (state.isEmpty()) throw new BusinessConflictException("Chưa khởi tạo sổ tài chính cho cửa hàng.");
    }

    private void assertEligible(ShopSettlementPeriodEntity period) {
        if (eligibility.countIneligibleCredits(period.getId(), clock.instant()) > 0) {
            throw new BusinessConflictException("Có hoàn tiền, tranh chấp hoặc giữ tiền mới; chưa thể chi kỳ này.");
        }
    }

    private ShopPayoutOperationEntity lockOperation(UUID actorId, String key, ShopPayoutOperationType type,
                                                     String hash) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key.trim()).matches()) {
            throw new BusinessConflictException("Thiếu hoặc sai Idempotency-Key.");
        }
        String normalized = key.trim();
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .setParameter("lockKey", "shop-payout-idempotency:" + actorId + ":" + normalized)
                .getSingleResult();
        ShopPayoutOperationEntity old = operations.findByActorIdAndIdempotencyKeyForUpdate(actorId, normalized)
                .orElse(null);
        if (old != null && (old.getOperationType() != type || !old.getRequestHash().equals(hash))) {
            throw idempotencyConflict();
        }
        return old;
    }

    private void saveOperation(UUID actorId, ShopPayoutOperationType type, String key, String hash,
                               UUID destinationId, UUID payoutId, Instant at) {
        operations.saveAndFlush(new ShopPayoutOperationEntity(actorId, type, key.trim(), hash,
                destinationId, payoutId, at));
    }

    private ShopPayoutView replayPayout(ShopPayoutOperationEntity operation, UUID expectedPayoutId) {
        if (operation.getPayoutId() == null || !expectedPayoutId.equals(operation.getPayoutId())) {
            throw idempotencyConflict();
        }
        return ShopPayoutView.from(payouts.findById(operation.getPayoutId())
                .orElseThrow(ShopPayoutService::idempotencyConflict));
    }

    private static String normalizeBankCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!BANK_CODE.matcher(normalized).matches()) throw new BusinessConflictException("Mã ngân hàng không hợp lệ.");
        return normalized;
    }

    private static String normalizeAccountNumber(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "");
        if (!ACCOUNT_NUMBER.matcher(normalized).matches()) {
            throw new BusinessConflictException("Số tài khoản phải có từ 6 đến 34 chữ số.");
        }
        return normalized;
    }

    private static String normalizeAccountName(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (!ACCOUNT_NAME.matcher(normalized).matches()) {
            throw new BusinessConflictException("Tên chủ tài khoản không hợp lệ.");
        }
        return normalized;
    }

    private static String normalizeVerificationReference(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 3 || normalized.length() > 190) {
            throw new BusinessConflictException("Cần ghi căn cứ xác minh tài khoản.");
        }
        return normalized;
    }

    private static String normalizeProviderReference(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PROVIDER_REFERENCE.matcher(normalized).matches()) {
            throw new BusinessConflictException("Mã tham chiếu chuyển khoản không hợp lệ.");
        }
        return normalized;
    }

    private static String limitNote(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 1000) throw new BusinessConflictException("Ghi chú vượt quá giới hạn.");
        return normalized;
    }

    private static String requestHash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = java.util.Arrays.stream(values).map(value -> value == null ? "" : value.toString())
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void requireSameShop(ShopSettlementPeriodEntity period, ShopPayoutEntity payout) {
        if (!Objects.equals(period.getShopId(), payout.getShopId())
                || !Objects.equals(period.getId(), payout.getSettlementPeriodId())) {
            throw new BusinessConflictException("Payout và kỳ settlement không cùng cửa hàng.");
        }
    }

    private static void requireAmountMatchesPeriod(ShopPayoutEntity payout, ShopSettlementPeriodEntity period) {
        if (payout.getAmount() != period.getNetAmount()) {
            throw new BusinessConflictException("Số tiền kỳ settlement đã thay đổi; hãy đối chiếu và duyệt lại.");
        }
    }

    private static void requireStatus(ShopPayoutEntity payout, ShopPayoutStatus required, String message) {
        if (payout.getStatus() != required) throw new BusinessConflictException(message);
    }

    private static void requireStatus(ShopSettlementPeriodEntity period, ShopSettlementPeriodStatus required,
                                      String message) {
        if (period.getStatus() != required) throw new BusinessConflictException(message);
    }

    private static BusinessConflictException idempotencyConflict() {
        return new BusinessConflictException("Idempotency-Key đã được dùng cho yêu cầu khác.");
    }
    private static BusinessConflictException missingPeriod() {
        return new BusinessConflictException("Không tìm thấy kỳ settlement.");
    }
    private static BusinessConflictException missingPayout() {
        return new BusinessConflictException("Không tìm thấy payout.");
    }
    private static BusinessConflictException missingDestination() {
        return new BusinessConflictException("Không tìm thấy tài khoản nhận tiền.");
    }
    private static AccessDeniedException deniedOwner() {
        return new AccessDeniedException("Chỉ chủ cửa hàng đang hoạt động được thực hiện thao tác này.");
    }
    private static AccessDeniedException deniedAdmin() {
        return new AccessDeniedException("Chỉ Admin đang hoạt động được xử lý payout.");
    }
}
