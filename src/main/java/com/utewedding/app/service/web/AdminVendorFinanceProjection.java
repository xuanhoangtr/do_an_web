package com.utewedding.app.service.web;

/** Platform-wide, read-only finance projection for Vendor-owned shops. */
public record AdminVendorFinanceProjection(long shopCount, long available, long pending, long held,
                                           long paid, long recovery, long sales, long refunds,
                                           long commission, long paidInRange) {
    public AdminVendorFinanceProjection {
        if (shopCount < 0) throw new IllegalArgumentException("Shop count cannot be negative.");
    }
}
