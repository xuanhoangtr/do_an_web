package com.utewedding.app.service.web;

import java.util.Locale;

/** Vietnamese labels for customer-visible order and delivery history. */
public final class TimelineStatusLabels {
    private TimelineStatusLabels() { }

    public static String subjectLabel(String subject) {
        if (subject == null || subject.isBlank()) return "Cập nhật trạng thái";
        String normalized = subject.toLowerCase(Locale.ROOT);
        if (normalized.contains("thanh toán") || normalized.contains("payment")) return "Thanh toán";
        if (normalized.contains("giao") || normalized.contains("fulfillment")
                || normalized.contains("shipment") || normalized.contains("shipping")
                || normalized.contains("delivery")) return "Giao hàng";
        if (normalized.contains("đơn") || normalized.contains("order")) return "Đơn hàng";
        if (normalized.contains("return") || normalized.contains("hoàn")) return "Trả hàng / hoàn tiền";
        return subject;
    }

    public static String statusLabel(String subject, Object rawStatus) {
        if (rawStatus == null) return "Chưa xác định";
        String status = rawStatus.toString().trim();
        if (status.isEmpty()) return "Chưa xác định";
        String normalizedSubject = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (normalizedSubject.contains("thanh toán") || normalizedSubject.contains("payment")) {
            return switch (status) {
                case "PENDING" -> "Chờ thanh toán";
                case "PROCESSING" -> "Đang xử lý thanh toán";
                case "SUCCEEDED", "SUCCESS" -> "Thanh toán thành công";
                case "FAILED" -> "Thanh toán thất bại";
                case "NOT_COLLECTED" -> "Chưa thu tiền";
                case "RECONCILIATION_REQUIRED" -> "Cần đối soát thanh toán";
                case "REFUND_PENDING" -> "Chờ hoàn tiền";
                case "PARTIALLY_REFUNDED" -> "Đã hoàn tiền một phần";
                case "REFUNDED" -> "Đã hoàn tiền";
                case "REFUND_FAILED" -> "Hoàn tiền thất bại";
                default -> StatusLabels.labelName(status);
            };
        }
        boolean delivery = normalizedSubject.contains("giao") || normalizedSubject.contains("fulfillment")
                || normalizedSubject.contains("shipment") || normalizedSubject.contains("shipping")
                || normalizedSubject.contains("delivery");
        if (delivery) {
            return switch (status) {
                case "NOT_READY", "PENDING" -> "Chưa sẵn sàng giao";
                case "CONFIRMED" -> "Đã xác nhận";
                case "PROCESSING" -> "Đang chuẩn bị hàng";
                case "PICKUP_PENDING" -> "Chờ đơn vị vận chuyển nhận hàng";
                case "PICKED_UP" -> "Đơn vị vận chuyển đã nhận hàng";
                case "IN_TRANSIT", "SHIPPING" -> "Đang giao hàng";
                case "PARTIALLY_DELIVERED" -> "Đã giao một phần";
                case "PARTIALLY_DELIVERED_RETURNED" -> "Đã giao một phần, có hàng hoàn";
                case "DELIVERED" -> "Đã giao thành công";
                case "DELIVERY_FAILED", "FAILED" -> "Giao hàng chưa thành công";
                case "RETRY_DELIVERY" -> "Chờ giao lại";
                case "RETURNING" -> "Đang hoàn hàng";
                case "RETURNED" -> "Đã hoàn hàng";
                case "CANCELLED" -> "Đã hủy giao hàng";
                case "SUCCESS", "SUCCEEDED" -> "Giao hàng thành công";
                default -> StatusLabels.labelName(status);
            };
        }
        boolean order = normalizedSubject.contains("đơn") || normalizedSubject.contains("order");
        if (order) {
            return switch (status) {
                case "PENDING" -> "Chờ thanh toán";
                case "PAYMENT_REVIEW" -> "Đang xác minh thanh toán";
                case "PAID" -> "Đã thanh toán";
                case "CANCELLED" -> "Đã hủy đơn hàng";
                case "EXPIRED" -> "Đơn hàng đã hết hạn";
                case "REFUNDED" -> "Đã hoàn tiền";
                default -> StatusLabels.labelName(status);
            };
        }
        return StatusLabels.labelName(status);
    }

    public static String reasonLabel(Object rawReason) {
        if (rawReason == null) return "";
        String reason = rawReason.toString().trim();
        return switch (reason) {
            case "CUSTOMER_ABSENT" -> "Không liên lạc được người nhận";
            case "WRONG_ADDRESS" -> "Sai địa chỉ";
            case "CUSTOMER_REFUSED" -> "Khách từ chối nhận hàng";
            case "DAMAGED" -> "Hàng hóa hư hỏng";
            case "OTHER" -> "Lý do khác";
            default -> StatusLabels.labelName(reason);
        };
    }
}
