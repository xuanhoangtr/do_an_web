package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.ShopSettlementPeriodStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.ShopFinanceStateEntity;
import com.utewedding.app.domain.entity.ShopSettlementPeriodEntity;
import com.utewedding.app.domain.entity.ShopSettlementPeriodEntryEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.ShopFinanceStateRepository;
import com.utewedding.app.repository.ShopSettlementEligibilityRepository;
import com.utewedding.app.repository.ShopSettlementPeriodEntryRepository;
import com.utewedding.app.repository.ShopSettlementPeriodRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.finance.ShopSettlementPeriodWindow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assigns eligible ledger sources exactly once and guards settlement state transitions. */
@Service
public class ShopSettlementPeriodService {
    private final ShopFinanceStateRepository financeStates;
    private final ShopSettlementEligibilityRepository eligibility;
    private final ShopSettlementPeriodRepository periods;
    private final ShopSettlementPeriodEntryRepository periodEntries;
    private final UserRepository users;
    private final Clock clock;

    public ShopSettlementPeriodService(ShopFinanceStateRepository financeStates,
                                       ShopSettlementEligibilityRepository eligibility,
                                       ShopSettlementPeriodRepository periods,
                                       ShopSettlementPeriodEntryRepository periodEntries,
                                       UserRepository users, Clock shopSettlementClock) {
        this.financeStates = financeStates;
        this.eligibility = eligibility;
        this.periods = periods;
        this.periodEntries = periodEntries;
        this.users = users;
        this.clock = shopSettlementClock;
    }

    /**
     * Scheduler and Admin repair entry point. Every shop transaction locks the same V101
     * finance anchor as ledger writers and approval requests; the assignment table also has
     * a unique settlement_entry_id constraint and ON CONFLICT idempotency.
     */
    @Transactional(rollbackFor = Exception.class)
    public int repairUnassignedEntries() {
        Instant now = clock.instant();
        int assigned = 0;
        for (UUID shopId : eligibility.findShopsWithUnassignedEntries(ShopDefaults.PLATFORM_SHOP_ID)) {
            lockFinanceState(shopId);
            if (eligibility.countEligibleUnassignedEntries(shopId, now) > 0) {
                ShopSettlementPeriodEntity period = periodForNewAssignments(shopId, now);
                int newlyAssigned = eligibility.assignEligibleUnassignedEntries(period.getId(), shopId, now);
                if (newlyAssigned > 0) {
                    refreshTotals(period);
                    periods.saveAndFlush(period);
                    assigned = Math.addExact(assigned, newlyAssigned);
                }
            }
            for (UUID periodId : eligibility.allocateOutstandingRecoveryOffsets(shopId)) {
                ShopSettlementPeriodEntity period = periods.findByIdForUpdate(periodId).orElseThrow();
                refreshTotals(period);
                periods.saveAndFlush(period);
            }
        }
        return assigned;
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopSettlementPeriodEntity approve(UUID periodId, UUID actorId) {
        requireFinanceAdmin(actorId);
        ShopSettlementPeriodEntity observed = findPeriod(periodId);
        lockFinanceState(observed.getShopId());
        ShopSettlementPeriodEntity period = periods.findByIdForUpdate(periodId).orElseThrow();
        if (period.getStatus() == ShopSettlementPeriodStatus.APPROVED) return period;
        requireStatus(period, ShopSettlementPeriodStatus.DRAFT, "Chỉ kỳ nháp mới được duyệt.");
        refreshPeriodsWithRecoveryOffsets(
                eligibility.allocateOutstandingRecoveryOffsetsForPeriod(period.getShopId(), period.getId()), period);
        refreshTotals(period);
        ensureRecoveryReservationsMatchTotals(period);
        requireSourcesStillEligible(period);
        if (periodEntries.findByPeriodIdOrderByCreatedAtAscIdAsc(periodId).isEmpty()) {
            throw new BusinessConflictException("Kỳ settlement chưa có bút toán để duyệt.");
        }
        period.approve(actorId, clock.instant());
        return periods.saveAndFlush(period);
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopSettlementPeriodEntity beginProcessing(UUID periodId, UUID actorId) {
        requireFinanceAdmin(actorId);
        ShopSettlementPeriodEntity observed = findPeriod(periodId);
        lockFinanceState(observed.getShopId());
        ShopSettlementPeriodEntity period = periods.findByIdForUpdate(periodId).orElseThrow();
        if (period.getStatus() == ShopSettlementPeriodStatus.PROCESSING) return period;
        requireStatus(period, ShopSettlementPeriodStatus.APPROVED, "Chỉ kỳ đã duyệt mới được chuyển xử lý.");
        List<UUID> staleDebits = eligibility.findUnassignedPostApprovalDebits(period);
        boolean hasPostApprovalDebits = eligibility.countPostApprovalDebits(period) > 0;
        List<UUID> staleRecoveryDebts = eligibility.findPostApprovalRecoveryDebtsApplicableToPeriod(period);
        if (hasPostApprovalDebits || !staleRecoveryDebts.isEmpty()) {
            if ((hasPostApprovalDebits && staleDebits.isEmpty())
                    || (hasPostApprovalDebits
                        && eligibility.countPostApprovalDebitsAssignedElsewhere(period) > 0)) {
                throw new BusinessConflictException(
                        "Kỳ duyệt có debit đã gán sang kỳ khác; cần Admin xử lý đối soát trước khi payout.");
            }
            ensureRecoveryReservationsMatchTotals(period);
            // Returning DRAFT commits the audited recalculation without beginning a transfer.
            // The payout caller must cancel its stale request and require a fresh period approval.
            UUID recalculationId = null;
            for (UUID debitEntryId : staleDebits) {
                recalculationId = eligibility.recordPeriodRecalculation(period, actorId, debitEntryId);
            }
            for (UUID recoveryDebtId : staleRecoveryDebts) {
                recalculationId = eligibility.recordRecoveryRecalculation(period, actorId, recoveryDebtId);
            }
            period.resetApprovalForRecalculation(recalculationId);
            periods.saveAndFlush(period);
            eligibility.releaseRecoveryOffsetsForRecalculation(period.getId());
            List<UUID> releasedRecoveryPeriods = eligibility.releaseRecoveryOffsetsForRecoveryRecalculation(
                    period.getId(), staleRecoveryDebts);
            refreshPeriodsWithRecoveryOffsets(releasedRecoveryPeriods, period);
            int recalculatedEntries = eligibility.assignPostApprovalDebits(
                    period.getId(), period.getShopId(), staleDebits, clock.instant());
            if (recalculatedEntries != staleDebits.size()) {
                throw new BusinessConflictException(
                        "Debit sources changed during settlement recalculation; retry Admin reconciliation.");
            }
            refreshTotals(period);
            periods.saveAndFlush(period);
            refreshPeriodsWithRecoveryOffsets(
                    eligibility.allocateOutstandingRecoveryOffsetsForPeriod(period.getShopId(), period.getId()), period);
            if (!staleRecoveryDebts.isEmpty()
                    && eligibility.countReservedRecoveryDebts(period.getId(), staleRecoveryDebts) == 0) {
                throw new BusinessConflictException(
                        "Recovery debt cannot be reserved against this period; payout remains blocked for reconciliation.");
            }
            return period;
        }
        ensureRecoveryReservationsMatchTotals(period);
        requireSourcesStillEligible(period);
        if (period.getNetAmount() <= 0) {
            throw new BusinessConflictException("Kỳ settlement không có số dư dương để xử lý payout.");
        }
        period.beginProcessing(actorId, clock.instant());
        return periods.saveAndFlush(period);
    }

    @Transactional(readOnly = true)
    public ShopSettlementPeriodEntity getPeriod(UUID periodId) { return findPeriod(periodId); }

    private ShopSettlementPeriodEntity periodForNewAssignments(UUID shopId, Instant now) {
        ShopSettlementPeriodWindow window = ShopSettlementPeriodWindow.containing(now);
        while (true) {
            var existing = periods.findByShopIdAndPeriodStartAt(shopId, window.startInclusive());
            if (existing.isEmpty()) {
                return periods.saveAndFlush(new ShopSettlementPeriodEntity(
                        shopId, window.startInclusive(), window.endExclusive()));
            }
            if (existing.get().getStatus() == ShopSettlementPeriodStatus.DRAFT) return existing.get();
            window = window.next();
        }
    }

    private void refreshTotals(ShopSettlementPeriodEntity period) {
        long credits = 0;
        long debits = 0;
        List<ShopSettlementPeriodEntryEntity> rows = periodEntries
                .findByPeriodIdOrderByCreatedAtAscIdAsc(period.getId());
        try {
            for (ShopSettlementPeriodEntryEntity row : rows) {
                if (row.getEntryDirection() == com.utewedding.app.domain.entity.ShopSettlementEntryEntity.Direction.CREDIT) {
                    credits = Math.addExact(credits, row.getEntryAmount());
                } else {
                    debits = Math.addExact(debits, row.getEntryAmount());
                }
            }
            var recoveryOffsets = eligibility.recoveryOffsetTotals(period.getId());
            period.replaceTotals(credits, debits, recoveryOffsets.amount(),
                    Math.addExact(rows.size(), recoveryOffsets.count()));
        } catch (ArithmeticException overflow) {
            throw new BusinessConflictException("Tổng số dư kỳ settlement vượt giới hạn ghi nhận.");
        }
    }

    private void refreshPeriodsWithRecoveryOffsets(List<UUID> periodIds, ShopSettlementPeriodEntity currentPeriod) {
        for (UUID periodId : periodIds) {
            ShopSettlementPeriodEntity period = periodId.equals(currentPeriod.getId())
                    ? currentPeriod : periods.findByIdForUpdate(periodId).orElseThrow();
            refreshTotals(period);
            periods.saveAndFlush(period);
        }
    }

    private void ensureRecoveryReservationsMatchTotals(ShopSettlementPeriodEntity period) {
        var reservations = eligibility.lockPeriodRecoveryOffsets(period.getId());
        long availableBeforeRecovery = period.getCreditAmount() - period.getDebitAmount();
        if (reservations.amount() != period.getRecoveryDebitAmount()
                || reservations.amount() > availableBeforeRecovery) {
            throw new BusinessConflictException(
                    "Recovery reservation does not match the approved settlement amount; run reconciliation first.");
        }
    }

    private void requireSourcesStillEligible(ShopSettlementPeriodEntity period) {
        if (eligibility.countIneligibleCredits(period.getId(), clock.instant()) > 0) {
            throw new BusinessConflictException(
                    "Có nguồn tiền trong kỳ không còn đủ điều kiện do return, refund, dispute hoặc hold.");
        }
    }

    private void lockFinanceState(UUID shopId) {
        if (financeStates.findByShopIdForUpdate(shopId).isEmpty()) {
            throw new BusinessConflictException("Chưa khởi tạo sổ tài chính cho cửa hàng.");
        }
    }

    private ShopSettlementPeriodEntity findPeriod(UUID periodId) {
        return periods.findById(periodId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy kỳ settlement."));
    }

    private void requireFinanceAdmin(UUID actorId) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getStatus() != UserStatus.ACTIVE
                || (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN)) {
            throw new AccessDeniedException("Chỉ Admin đang hoạt động được duyệt hoặc xử lý settlement.");
        }
    }

    private static void requireStatus(ShopSettlementPeriodEntity period, ShopSettlementPeriodStatus required,
                                      String message) {
        if (period.getStatus() != required) throw new BusinessConflictException(message);
    }
}
