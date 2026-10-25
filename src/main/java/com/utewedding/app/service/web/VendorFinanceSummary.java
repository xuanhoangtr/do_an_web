package com.utewedding.app.service.web;

/** Read-only, shop-scoped finance projection. Held is informational and may overlap timing buckets. */
public record VendorFinanceSummary(long available, long pending, long held, long paid, long recovery,
                                   long sales, long refunds, long commission, long paidInRange) { }
