package com.utewedding.app.domain;

public enum DeliveryFailureReason {
    CUSTOMER_ABSENT,
    WRONG_ADDRESS,
    CUSTOMER_REFUSED,
    DAMAGED,
    OTHER;

    public String label() {
        return switch (this) {
            case CUSTOMER_ABSENT -> "Khách không có mặt";
            case WRONG_ADDRESS -> "Sai địa chỉ";
            case CUSTOMER_REFUSED -> "Khách từ chối nhận hàng";
            case DAMAGED -> "Hàng hóa bị hư hỏng";
            case OTHER -> "Lý do khác";
        };
    }

    public static DeliveryFailureReason parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
