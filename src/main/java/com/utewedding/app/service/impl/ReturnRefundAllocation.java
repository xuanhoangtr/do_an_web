package com.utewedding.app.service.impl;

public final class ReturnRefundAllocation {
    private ReturnRefundAllocation() {}

    public static long entitlement(long netPaidAmount, int quantity, int purchasedQuantity) {
        if (netPaidAmount < 0 || quantity < 0 || purchasedQuantity <= 0 || quantity > purchasedQuantity) {
            throw new IllegalArgumentException("Invalid refund allocation inputs");
        }
        return Math.floorDiv(Math.multiplyExact(netPaidAmount, quantity), purchasedQuantity);
    }

    public static long cumulativeDelta(long netPaidAmount, int beforeQuantity, int afterQuantity, int purchasedQuantity) {
        if (afterQuantity < beforeQuantity) throw new IllegalArgumentException("afterQuantity must not decrease");
        return Math.subtractExact(entitlement(netPaidAmount, afterQuantity, purchasedQuantity), entitlement(netPaidAmount, beforeQuantity, purchasedQuantity));
    }
}