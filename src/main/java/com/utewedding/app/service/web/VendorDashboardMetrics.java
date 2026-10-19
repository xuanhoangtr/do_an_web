package com.utewedding.app.service.web;

/** Shop-scoped operational metrics; ledger net is not customer-paid cash or a payout balance. */
public record VendorDashboardMetrics(long ordersInRange, long openOrders,
                                     long activeProducts, long stockUnits,
                                     long lowStockProducts, long ledgerNet) {
    public VendorDashboardMetrics {
        if (ordersInRange < 0 || openOrders < 0 || activeProducts < 0
                || stockUnits < 0 || lowStockProducts < 0) {
            throw new IllegalArgumentException("Dashboard counts cannot be negative.");
        }
    }

    public String ledgerNetDisplay() {
        java.text.NumberFormat number = java.text.NumberFormat.getIntegerInstance(
                java.util.Locale.forLanguageTag("vi-VN"));
        String sign = ledgerNet < 0 ? "−" : "";
        return sign + number.format(java.math.BigInteger.valueOf(ledgerNet).abs()) + " đ";
    }
}
