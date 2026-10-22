package com.utewedding.app.service.impl;

import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherUsageSlot;
import com.utewedding.app.domain.entity.OrderAppliedDiscountEntity;
import com.utewedding.app.domain.entity.OrderAppliedDiscountItemAllocationEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.ReturnResolutionItemEntity;
import com.utewedding.app.domain.entity.ShopOrderFinancialSnapshotEntity;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cumulative seller payable reversal, based only on checkout and commission snapshots. */
public final class ReturnSellerEntitlement {
    private ReturnSellerEntitlement() { }

    public static long calculate(ShopOrderFinancialSnapshotEntity snapshot,
                                 List<OrderItemEntity> orderItems,
                                 List<OrderAppliedDiscountEntity> discounts,
                                 List<OrderAppliedDiscountItemAllocationEntity> discountAllocations,
                                 Map<UUID, UUID> orderItemByReturnCaseItem,
                                 List<ReturnResolutionItemEntity> resolutionLines) {
        if (snapshot == null) throw new IllegalArgumentException("Missing immutable order financial snapshot.");
        if (snapshot.getCommissionBase() < 0 || snapshot.getCommissionAmount() < 0
                || snapshot.getVendorPayable() < 0
                || snapshot.getCommissionAmount() > snapshot.getCommissionBase()
                || snapshot.getCommissionBase() - snapshot.getCommissionAmount() != snapshot.getVendorPayable()) {
            throw new IllegalArgumentException("Invalid immutable order commission snapshot.");
        }

        Map<UUID, OrderItemEntity> itemsById = new HashMap<>();
        Map<UUID, Long> shopDiscountByDiscountId = new HashMap<>();
        Map<UUID, OrderAppliedDiscountEntity> discountsById = new HashMap<>();
        Map<UUID, Long> allocatedShopDiscountByItem = new HashMap<>();
        long merchandiseGross = 0;
        for (OrderItemEntity item : orderItems) {
            if (item.getQuantity() <= 0 || item.getLineTotal() < 0) {
                throw new IllegalArgumentException("Invalid immutable order-item snapshot.");
            }
            itemsById.put(item.getId(), item);
            merchandiseGross = Math.addExact(merchandiseGross, item.getLineTotal());
        }
        for (OrderAppliedDiscountEntity discount : discounts) {
            discountsById.put(discount.getId(), discount);
            if (discount.getSlot() == VoucherUsageSlot.MERCHANDISE
                    && discount.getFundingSource() == VoucherFundingSource.SHOP) {
                shopDiscountByDiscountId.put(discount.getId(), discount.getDiscountAmount());
            }
        }

        long allocatedShopDiscount = 0;
        for (OrderAppliedDiscountItemAllocationEntity allocation : discountAllocations) {
            OrderAppliedDiscountEntity discount = discountsById.get(allocation.getOrderAppliedDiscountId());
            if (discount != null && shopDiscountByDiscountId.containsKey(discount.getId())) {
                if (!itemsById.containsKey(allocation.getOrderItemId()) || allocation.getAmount() < 0) {
                    throw new IllegalArgumentException("Invalid shop-funded discount allocation snapshot.");
                }
                allocatedShopDiscountByItem.merge(allocation.getOrderItemId(), allocation.getAmount(), Math::addExact);
                allocatedShopDiscount = Math.addExact(allocatedShopDiscount, allocation.getAmount());
            }
        }

        long expectedShopDiscount = shopDiscountByDiscountId.values().stream()
                .mapToLong(Long::longValue).reduce(0, Math::addExact);
        if (merchandiseGross != snapshot.getMerchandiseGross()
                || expectedShopDiscount != snapshot.getShopFundedDiscount()
                || allocatedShopDiscount != expectedShopDiscount) {
            throw new IllegalArgumentException("Order financial and funding snapshots do not reconcile.");
        }

        Map<UUID, Long> baseByItem = new HashMap<>();
        long commissionBase = 0;
        for (OrderItemEntity item : orderItems) {
            long base = Math.subtractExact(item.getLineTotal(), allocatedShopDiscountByItem.getOrDefault(item.getId(), 0L));
            if (base < 0) throw new IllegalArgumentException("Shop-funded discount exceeds its merchandise line.");
            baseByItem.put(item.getId(), base);
            commissionBase = Math.addExact(commissionBase, base);
        }
        if (commissionBase != snapshot.getCommissionBase()) {
            throw new IllegalArgumentException("Commission base does not match the immutable funding snapshot.");
        }

        Map<UUID, Long> commissionByItem = allocateCommission(snapshot.getCommissionAmount(), commissionBase, baseByItem);
        Map<UUID, Long> payableByItem = new HashMap<>();
        for (var row : baseByItem.entrySet()) {
            long commission = commissionByItem.getOrDefault(row.getKey(), 0L);
            payableByItem.put(row.getKey(), Math.subtractExact(row.getValue(), commission));
        }

        Map<UUID, Integer> returnedQuantity = new HashMap<>();
        long reversal = 0;
        for (ReturnResolutionItemEntity line : resolutionLines) {
            UUID orderItemId = orderItemByReturnCaseItem.get(line.getReturnCaseItemId());
            OrderItemEntity item = orderItemId == null ? null : itemsById.get(orderItemId);
            if (item == null || line.getQuantity() <= 0) {
                throw new IllegalArgumentException("Return line does not match its immutable order item.");
            }
            int before = returnedQuantity.getOrDefault(orderItemId, item.getReturnResolvedQuantity());
            int after = Math.addExact(before, line.getQuantity());
            if (after > item.getQuantity()) throw new IllegalArgumentException("Returned quantity exceeds purchase quantity.");
            returnedQuantity.put(orderItemId, after);
            reversal = Math.addExact(reversal, ReturnRefundAllocation.cumulativeDelta(
                    payableByItem.getOrDefault(orderItemId, 0L), before, after, item.getQuantity()));
        }
        return reversal;
    }

    private static Map<UUID, Long> allocateCommission(long commissionAmount, long commissionBase,
                                                       Map<UUID, Long> baseByItem) {
        Map<UUID, Long> result = new HashMap<>();
        if (commissionBase == 0) {
            if (commissionAmount != 0) throw new IllegalArgumentException("Commission exists without a base.");
            return result;
        }

        record Share(UUID itemId, long amount, BigInteger remainder) { }
        List<Share> shares = new ArrayList<>();
        long allocated = 0;
        BigInteger denominator = BigInteger.valueOf(commissionBase);
        BigInteger total = BigInteger.valueOf(commissionAmount);
        for (var row : baseByItem.entrySet()) {
            BigInteger[] division = total.multiply(BigInteger.valueOf(row.getValue())).divideAndRemainder(denominator);
            long amount = division[0].longValueExact();
            allocated = Math.addExact(allocated, amount);
            result.put(row.getKey(), amount);
            shares.add(new Share(row.getKey(), amount, division[1]));
        }
        long remainder = Math.subtractExact(commissionAmount, allocated);
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparing(Share::itemId));
        if (remainder > shares.size()) throw new IllegalArgumentException("Commission allocation does not reconcile.");
        for (int i = 0; i < remainder; i++) {
            Share share = shares.get(i);
            result.put(share.itemId(), Math.addExact(share.amount(), 1));
        }
        return result;
    }
}
