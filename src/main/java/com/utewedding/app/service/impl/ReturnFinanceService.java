package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.ShopFinanceHoldSourceType;
import com.utewedding.app.domain.ShopFinanceHoldStatus;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.ShopFinanceHoldEntity;
import com.utewedding.app.domain.entity.ShopOrderFinancialSnapshotEntity;
import com.utewedding.app.domain.entity.ShopSettlementEntryEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.OrderAppliedDiscountItemAllocationRepository;
import com.utewedding.app.repository.OrderAppliedDiscountRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.ReturnCaseItemRepository;
import com.utewedding.app.repository.ShopFinanceHoldRepository;
import com.utewedding.app.repository.ShopFinanceStateRepository;
import com.utewedding.app.repository.ShopOrderFinancialSnapshotRepository;
import com.utewedding.app.repository.ShopSettlementEntryRepository;
import com.utewedding.app.domain.entity.ReturnCaseEntity;
import com.utewedding.app.domain.entity.ReturnResolutionEntity;
import com.utewedding.app.domain.entity.ReturnResolutionItemEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Synchronous finance-anchor updates for return holds and immutable seller reversals. */
@Service
public class ReturnFinanceService {
    private final ShopFinanceStateRepository financeStates;
    private final ShopFinanceHoldRepository holds;
    private final ShopOrderFinancialSnapshotRepository snapshots;
    private final ShopSettlementEntryRepository entries;
    private final OrderItemRepository orderItems;
    private final OrderAppliedDiscountRepository discounts;
    private final OrderAppliedDiscountItemAllocationRepository discountAllocations;
    private final ReturnCaseItemRepository caseItems;
    private final ShopPayoutRecoveryService payoutRecovery;

    public ReturnFinanceService(ShopFinanceStateRepository financeStates, ShopFinanceHoldRepository holds,
                                ShopOrderFinancialSnapshotRepository snapshots,
                                ShopSettlementEntryRepository entries, OrderItemRepository orderItems,
                                OrderAppliedDiscountRepository discounts,
                                OrderAppliedDiscountItemAllocationRepository discountAllocations,
                                ReturnCaseItemRepository caseItems, ShopPayoutRecoveryService payoutRecovery) {
        this.financeStates = financeStates;
        this.holds = holds;
        this.snapshots = snapshots;
        this.entries = entries;
        this.orderItems = orderItems;
        this.discounts = discounts;
        this.discountAllocations = discountAllocations;
        this.caseItems = caseItems;
        this.payoutRecovery = payoutRecovery;
    }

    /** Commits the return hold in the preparation transaction before any provider call can run. */
    @Transactional(rollbackFor = Exception.class)
    public long ensureRefundHold(OrderEntity order, ReturnCaseEntity returnCase,
                                 ReturnResolutionEntity resolution,
                                 List<ReturnResolutionItemEntity> resolutionLines) {
        validateOrderScope(order, returnCase, resolution);
        if (ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId())) return 0;
        lockFinanceState(order.getShopId());
        long amount = sellerReversal(order, resolutionLines);
        ShopFinanceHoldEntity hold = holds.findByShopIdAndSourceTypeAndSourceId(
                order.getShopId(), ShopFinanceHoldSourceType.RETURN_RESOLUTION, resolution.getId()).orElse(null);
        if (hold == null) {
            hold = holds.saveAndFlush(new ShopFinanceHoldEntity(order.getShopId(), order.getId(),
                    ShopFinanceHoldSourceType.RETURN_RESOLUTION, resolution.getId(), "RETURN_REFUND", amount));
        } else {
            if (!order.getId().equals(hold.getOrderId()) || hold.getAmount() != amount) {
                throw new BusinessConflictException("Finance hold does not match the immutable return allocation.");
            }
            hold.reactivate();
            holds.saveAndFlush(hold);
        }
        return amount;
    }

    /** Appends one funding-aware seller debit and releases the matching hold atomically. */
    @Transactional(rollbackFor = Exception.class)
    public long recordRefund(OrderEntity order, ReturnCaseEntity returnCase, ReturnResolutionEntity resolution,
                             List<ReturnResolutionItemEntity> resolutionLines, UUID actorId) {
        validateOrderScope(order, returnCase, resolution);
        if (ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId())) return 0;
        lockFinanceState(order.getShopId());
        long amount = sellerReversal(order, resolutionLines);
        ShopFinanceHoldEntity hold = holds.findByShopIdAndSourceTypeAndSourceId(order.getShopId(),
                ShopFinanceHoldSourceType.RETURN_RESOLUTION, resolution.getId()).orElse(null);
        String sourceIdentity = "return-refund:" + resolution.getId();
        ShopSettlementEntryEntity existing = entries.findBySourceIdentity(sourceIdentity).orElse(null);
        ShopSettlementEntryEntity refundEntry;
        if (existing == null) {
            if (hold == null || hold.getStatus() != ShopFinanceHoldStatus.ACTIVE || hold.getAmount() != amount) {
                throw new BusinessConflictException("Refund cannot be recorded without its matching active finance hold.");
            }
            refundEntry = entries.saveAndFlush(new ShopSettlementEntryEntity(order.getShopId(), order.getId(),
                    ShopSettlementEntryEntity.Type.REFUND, ShopSettlementEntryEntity.Direction.DEBIT,
                    amount, sourceIdentity, returnCase.getCaseNumber(), Instant.now()));
        } else if (!order.getShopId().equals(existing.getShopId()) || !order.getId().equals(existing.getOrderId())
                || existing.getEntryType() != ShopSettlementEntryEntity.Type.REFUND
                || existing.getDirection() != ShopSettlementEntryEntity.Direction.DEBIT
                || existing.getAmount() != amount) {
            throw new BusinessConflictException("Existing return ledger entry does not match its immutable resolution.");
        } else {
            refundEntry = existing;
        }
        payoutRecovery.recordRefundRecovery(refundEntry);

        if (hold != null && hold.getStatus() == ShopFinanceHoldStatus.ACTIVE) {
            hold.release(actorId);
            holds.saveAndFlush(hold);
        }
        return amount;
    }

    /** Definite provider failure releases the attempt's hold; a retry recreates it under the same source. */
    @Transactional(rollbackFor = Exception.class)
    public void releaseRefundHold(OrderEntity order, ReturnCaseEntity returnCase,
                                  ReturnResolutionEntity resolution, UUID actorId) {
        validateOrderScope(order, returnCase, resolution);
        if (ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId())) return;
        lockFinanceState(order.getShopId());
        holds.findByShopIdAndSourceTypeAndSourceId(order.getShopId(),
                ShopFinanceHoldSourceType.RETURN_RESOLUTION, resolution.getId())
                .ifPresent(hold -> {
                    if (hold.getStatus() == ShopFinanceHoldStatus.ACTIVE) {
                        hold.release(actorId);
                        holds.saveAndFlush(hold);
                    }
                });
    }

    private long sellerReversal(OrderEntity order, List<ReturnResolutionItemEntity> resolutionLines) {
        ShopOrderFinancialSnapshotEntity snapshot = snapshots.findByOrderId(order.getId())
                .orElseThrow(() -> new BusinessConflictException(
                        "Thiếu ảnh chụp tài chính của đơn; chưa thể đối chiếu phần công nợ cửa hàng."));
        var orderItemRows = orderItems.findByOrderIdOrderByIdAsc(order.getId());
        var discountRows = discounts.findAllByOrderIdOrderBySlotAsc(order.getId());
        var allocationRows = discountAllocations.findAllByOrderIdOrderByOrderItemIdAsc(order.getId());
        Map<UUID, UUID> itemByCaseLine = resolutionLines.stream()
                .map(ReturnResolutionItemEntity::getReturnCaseItemId)
                .map(caseItems::findById)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(item -> item.getId(), item -> item.getOrderItemId()));
        if (itemByCaseLine.size() != resolutionLines.size()) {
            throw new BusinessConflictException("Thiếu dòng đơn gốc để đối chiếu hoàn tiền.");
        }
        try {
            return ReturnSellerEntitlement.calculate(snapshot, orderItemRows, discountRows, allocationRows,
                    itemByCaseLine, resolutionLines);
        } catch (ArithmeticException | IllegalArgumentException failure) {
            throw new BusinessConflictException("Ảnh chụp đơn hàng không khớp phân bổ công nợ khi hoàn tiền.");
        }
    }

    private void lockFinanceState(UUID shopId) {
        if (financeStates.findByShopIdForUpdate(shopId).isEmpty()) {
            throw new BusinessConflictException("Chưa khởi tạo sổ tài chính cho cửa hàng.");
        }
    }

    private static void validateOrderScope(OrderEntity order, ReturnCaseEntity returnCase,
                                           ReturnResolutionEntity resolution) {
        if (order == null || returnCase == null || resolution == null
                || !order.getId().equals(returnCase.getOrderId())
                || !order.getShopId().equals(returnCase.getShopId())
                || !returnCase.getId().equals(resolution.getReturnCaseId())) {
            throw new BusinessConflictException("Đơn, cửa hàng và resolution không khớp nhau.");
        }
    }
}
