package com.utewedding.app.service.web;

/** Vietnamese labels for finance projections; persisted enum values remain internal. */
public final class VendorFinanceLabels {
    private VendorFinanceLabels() { }

    public static String kind(String value) {
        if (value == null) return "Tài chính";
        return switch (value) {
            case "LEDGER" -> "Sổ tài chính";
            case "PERIOD" -> "Kỳ đối soát";
            case "PAYOUT" -> "Khoản chi";
            default -> "Tài chính";
        };
    }

    public static String label(String value) {
        if (value == null) return "Khoản ghi";
        return switch (value) {
            case "SALE" -> "Doanh thu hàng hóa";
            case "REFUND" -> "Hoàn tiền";
            case "ADJUSTMENT" -> "Điều chỉnh";
            case "RECOVERY" -> "Thu hồi";
            case "Kỳ đối soát" -> value;
            case "Lệnh chi" -> value;
            default -> value;
        };
    }

    public static String status(String value) {
        if (value == null) return "—";
        return switch (value) {
            case "CREDIT" -> "Ghi có";
            case "DEBIT" -> "Ghi nợ";
            case "DRAFT" -> "Đang tổng hợp";
            case "APPROVED" -> "Đã duyệt";
            case "PROCESSING" -> "Đang chuyển khoản";
            case "PAID" -> "Đã thanh toán";
            case "REQUESTED" -> "Đang chờ xử lý";
            case "CANCELLED" -> "Đã hủy";
            default -> value;
        };
    }
}
