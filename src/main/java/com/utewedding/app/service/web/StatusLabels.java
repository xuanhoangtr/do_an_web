/*
 * Decompiled with CFR 0.152.
 */
package com.utewedding.app.service.web;

import com.utewedding.app.domain.CodReconciliationStatus;
import com.utewedding.app.domain.CodRemittanceStatus;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.OverallFulfillmentStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.PrivacyRequestStatus;
import com.utewedding.app.domain.ProductReviewStatus;
import com.utewedding.app.domain.ReturnCaseStatus;
import com.utewedding.app.domain.ReturnDisputeStatus;
import com.utewedding.app.domain.ReturnShipmentStatus;
import com.utewedding.app.domain.RiskEventStatus;
import com.utewedding.app.domain.SupportPriority;
import com.utewedding.app.domain.SupportTicketStatus;
import com.utewedding.app.domain.WeddingStatus;
import com.utewedding.app.service.web.OrderHistoryGroup;
import java.util.Locale;

public final class StatusLabels {
    private StatusLabels() {
    }

    public static String label(Enum<?> status) {
        if (status == null) {
            return "Ch\u01b0a x\u00e1c \u0111\u1ecbnh";
        }
        if (status instanceof OrderStatus) {
            OrderStatus value = (OrderStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case OrderStatus.PENDING -> "Ch\u1edd thanh to\u00e1n";
                case OrderStatus.PAYMENT_REVIEW -> "\u0110ang x\u00e1c minh thanh to\u00e1n";
                case OrderStatus.PAID -> "\u0110\u00e3 thanh to\u00e1n";
                case OrderStatus.CANCELLED -> "\u0110\u00e3 h\u1ee7y";
                case OrderStatus.EXPIRED -> "\u0110\u00e3 h\u1ebft h\u1ea1n";
                case OrderStatus.REFUNDED -> "\u0110\u00e3 ho\u00e0n ti\u1ec1n";
            };
        }
        if (status instanceof PaymentStatus) {
            PaymentStatus value = (PaymentStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case PaymentStatus.PENDING -> "Ch\u1edd thanh to\u00e1n";
                case PaymentStatus.PROCESSING -> "\u0110ang x\u1eed l\u00fd thanh to\u00e1n";
                case PaymentStatus.SUCCEEDED -> "Thanh to\u00e1n th\u00e0nh c\u00f4ng";
                case PaymentStatus.FAILED -> "Thanh to\u00e1n th\u1ea5t b\u1ea1i";
                case PaymentStatus.NOT_COLLECTED -> "Ch\u01b0a thu ti\u1ec1n";
                case PaymentStatus.RECONCILIATION_REQUIRED -> "C\u1ea7n \u0111\u1ed1i so\u00e1t";
                case PaymentStatus.REFUND_PENDING -> "Ch\u1edd ho\u00e0n ti\u1ec1n";
                case PaymentStatus.PARTIALLY_REFUNDED -> "\u0110\u00e3 ho\u00e0n m\u1ed9t ph\u1ea7n";
                case PaymentStatus.REFUNDED -> "\u0110\u00e3 ho\u00e0n ti\u1ec1n";
                case PaymentStatus.REFUND_FAILED -> "Ho\u00e0n ti\u1ec1n th\u1ea5t b\u1ea1i";
            };
        }
        if (status instanceof FulfillmentStatus) {
            FulfillmentStatus value = (FulfillmentStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case FulfillmentStatus.CONFIRMED -> "\u0110\u00e3 x\u00e1c nh\u1eadn";
                case FulfillmentStatus.PROCESSING -> "\u0110ang chu\u1ea9n b\u1ecb";
                case FulfillmentStatus.PICKUP_PENDING -> "Ch\u1edd l\u1ea5y h\u00e0ng";
                case FulfillmentStatus.PICKED_UP -> "\u0110\u00e3 l\u1ea5y h\u00e0ng";
                case FulfillmentStatus.IN_TRANSIT, FulfillmentStatus.SHIPPING -> "\u0110ang giao";
                case FulfillmentStatus.DELIVERED -> "\u0110\u00e3 giao";
                case FulfillmentStatus.DELIVERY_FAILED -> "Giao th\u1ea5t b\u1ea1i";
                case FulfillmentStatus.RETRY_DELIVERY -> "\u0110ang giao l\u1ea1i";
                case FulfillmentStatus.RETURNING -> "\u0110ang ho\u00e0n h\u00e0ng";
                case FulfillmentStatus.RETURNED -> "\u0110\u00e3 ho\u00e0n h\u00e0ng";
                case FulfillmentStatus.CANCELLED -> "\u0110\u00e3 h\u1ee7y giao h\u00e0ng";
            };
        }
        if (status instanceof OverallFulfillmentStatus) {
            OverallFulfillmentStatus value = (OverallFulfillmentStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case OverallFulfillmentStatus.NOT_READY -> "Ch\u01b0a s\u1eb5n s\u00e0ng giao";
                case OverallFulfillmentStatus.CONFIRMED -> "\u0110\u00e3 x\u00e1c nh\u1eadn";
                case OverallFulfillmentStatus.PROCESSING -> "\u0110ang chu\u1ea9n b\u1ecb";
                case OverallFulfillmentStatus.PICKUP_PENDING -> "Ch\u1edd l\u1ea5y h\u00e0ng";
                case OverallFulfillmentStatus.PICKED_UP -> "\u0110\u00e3 l\u1ea5y h\u00e0ng";
                case OverallFulfillmentStatus.IN_TRANSIT, OverallFulfillmentStatus.SHIPPING -> "\u0110ang giao";
                case OverallFulfillmentStatus.PARTIALLY_DELIVERED -> "\u0110\u00e3 giao m\u1ed9t ph\u1ea7n";
                case OverallFulfillmentStatus.PARTIALLY_DELIVERED_RETURNED -> "\u0110\u00e3 giao m\u1ed9t ph\u1ea7n, c\u00f3 h\u00e0ng ho\u00e0n";
                case OverallFulfillmentStatus.DELIVERED -> "\u0110\u00e3 giao";
                case OverallFulfillmentStatus.DELIVERY_FAILED -> "Giao th\u1ea5t b\u1ea1i";
                case OverallFulfillmentStatus.RETURNING -> "\u0110ang ho\u00e0n h\u00e0ng";
                case OverallFulfillmentStatus.RETURNED -> "\u0110\u00e3 ho\u00e0n h\u00e0ng";
                case OverallFulfillmentStatus.CANCELLED -> "\u0110\u00e3 h\u1ee7y giao h\u00e0ng";
            };
        }
        if (status instanceof ProductReviewStatus) {
            ProductReviewStatus value = (ProductReviewStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case ProductReviewStatus.PENDING -> "Ch\u1edd duy\u1ec7t";
                case ProductReviewStatus.APPROVED -> "\u0110\u00e3 \u0111\u0103ng";
                case ProductReviewStatus.REJECTED -> "B\u1ecb t\u1eeb ch\u1ed1i";
                case ProductReviewStatus.HIDDEN -> "\u0110\u00e3 \u1ea9n";
            };
        }
        if (status instanceof WeddingStatus) {
            WeddingStatus value = (WeddingStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case WeddingStatus.DRAFT -> "B\u1ea3n nh\u00e1p";
                case WeddingStatus.READY -> "S\u1eb5n s\u00e0ng";
                case WeddingStatus.PUBLISHED -> "\u0110\u00e3 xu\u1ea5t b\u1ea3n";
                case WeddingStatus.UNPUBLISHED -> "\u0110\u00e3 g\u1ee1";
                case WeddingStatus.ARCHIVED -> "\u0110\u00e3 l\u01b0u tr\u1eef";
                case WeddingStatus.SUSPENDED -> "T\u1ea1m ng\u01b0ng";
            };
        }
        if (status instanceof PrivacyRequestStatus) {
            PrivacyRequestStatus value = (PrivacyRequestStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case PrivacyRequestStatus.PENDING -> "Ch\u1edd qu\u1ea3n tr\u1ecb vi\u00ean duy\u1ec7t";
                case PrivacyRequestStatus.IN_PROGRESS -> "\u0110ang x\u1eed l\u00fd";
                case PrivacyRequestStatus.COMPLETED -> "\u0110\u00e3 ho\u00e0n t\u1ea5t";
                case PrivacyRequestStatus.PARTIALLY_COMPLETED -> "Ho\u00e0n t\u1ea5t m\u1ed9t ph\u1ea7n";
                case PrivacyRequestStatus.REJECTED -> "B\u1ecb t\u1eeb ch\u1ed1i";
            };
        }
        if (status instanceof CodReconciliationStatus) {
            CodReconciliationStatus value = (CodReconciliationStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case CodReconciliationStatus.OPEN -> "\u0110ang m\u1edf";
                case CodReconciliationStatus.AWAITING_AMOUNT -> "Ch\u1edd x\u00e1c nh\u1eadn s\u1ed1 ti\u1ec1n";
                case CodReconciliationStatus.RECONCILED -> "\u0110\u00e3 \u0111\u1ed1i so\u00e1t";
                case CodReconciliationStatus.NOT_COLLECTED -> "Ch\u01b0a thu";
                case CodReconciliationStatus.REVIEW_REQUIRED -> "C\u1ea7n ki\u1ec3m tra";
                case CodReconciliationStatus.RESOLVED -> "\u0110\u00e3 x\u1eed l\u00fd";
            };
        }
        if (status instanceof CodRemittanceStatus) {
            CodRemittanceStatus value = (CodRemittanceStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case CodRemittanceStatus.NOT_DUE -> "Kh\u00f4ng c\u1ea7n n\u1ed9p";
                case CodRemittanceStatus.PENDING -> "Shipper c\u1ea7n n\u1ed9p v\u1ec1 shop";
                case CodRemittanceStatus.RECEIVED -> "Shop \u0111\u00e3 nh\u1eadn ti\u1ec1n";
            };
        }
        if (status instanceof ReturnCaseStatus) {
            ReturnCaseStatus value = (ReturnCaseStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case ReturnCaseStatus.REQUESTED -> "\u0110\u00e3 y\u00eau c\u1ea7u";
                case ReturnCaseStatus.UNDER_REVIEW -> "\u0110ang duy\u1ec7t";
                case ReturnCaseStatus.APPROVED -> "\u0110\u00e3 duy\u1ec7t";
                case ReturnCaseStatus.REJECTED -> "B\u1ecb t\u1eeb ch\u1ed1i";
                case ReturnCaseStatus.AWAITING_RETURN -> "Ch\u1edd g\u1eedi h\u00e0ng";
                case ReturnCaseStatus.RETURN_IN_TRANSIT -> "\u0110ang g\u1eedi tr\u1ea3";
                case ReturnCaseStatus.RECEIVED -> "\u0110\u00e3 nh\u1eadn h\u00e0ng";
                case ReturnCaseStatus.INSPECTED -> "\u0110\u00e3 ki\u1ec3m tra";
                case ReturnCaseStatus.RESOLUTION_PENDING -> "Ch\u1edd gi\u1ea3i quy\u1ebft";
                case ReturnCaseStatus.REFUND_PENDING -> "Ch\u1edd ho\u00e0n ti\u1ec1n";
                case ReturnCaseStatus.REFUND_FAILED -> "Ho\u00e0n ti\u1ec1n th\u1ea5t b\u1ea1i";
                case ReturnCaseStatus.EXCHANGE_PENDING -> "Ch\u1edd \u0111\u1ed5i h\u00e0ng";
                case ReturnCaseStatus.RESOLVED -> "\u0110\u00e3 gi\u1ea3i quy\u1ebft";
                case ReturnCaseStatus.DISPUTED -> "\u0110ang tranh ch\u1ea5p";
                case ReturnCaseStatus.CLOSED -> "\u0110\u00e3 \u0111\u00f3ng";
                case ReturnCaseStatus.EXPIRED -> "\u0110\u00e3 h\u1ebft h\u1ea1n";
                case ReturnCaseStatus.CANCELLED -> "\u0110\u00e3 h\u1ee7y";
            };
        }
        if (status instanceof ReturnDisputeStatus) {
            ReturnDisputeStatus value = (ReturnDisputeStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case ReturnDisputeStatus.OPEN -> "\u0110ang m\u1edf";
                case ReturnDisputeStatus.UPHELD -> "Gi\u1eef quy\u1ebft \u0111\u1ecbnh";
                case ReturnDisputeStatus.OVERTURNED -> "\u0110\u00e3 \u0111\u1ea3o quy\u1ebft \u0111\u1ecbnh";
                case ReturnDisputeStatus.CLOSED -> "\u0110\u00e3 \u0111\u00f3ng";
            };
        }
        if (status instanceof ReturnShipmentStatus) {
            ReturnShipmentStatus value = (ReturnShipmentStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case ReturnShipmentStatus.CREATED -> "\u0110\u00e3 t\u1ea1o v\u1eadn \u0111\u01a1n";
                case ReturnShipmentStatus.IN_TRANSIT -> "\u0110ang v\u1eadn chuy\u1ec3n";
                case ReturnShipmentStatus.DELIVERED -> "\u0110\u00e3 giao";
                case ReturnShipmentStatus.CANCELLED -> "\u0110\u00e3 h\u1ee7y v\u1eadn chuy\u1ec3n";
            };
        }
        if (status instanceof SupportTicketStatus) {
            SupportTicketStatus value = (SupportTicketStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case SupportTicketStatus.OPEN -> "M\u1edbi m\u1edf";
                case SupportTicketStatus.IN_PROGRESS -> "\u0110ang x\u1eed l\u00fd";
                case SupportTicketStatus.WAITING_CUSTOMER -> "Ch\u1edd kh\u00e1ch h\u00e0ng";
                case SupportTicketStatus.RESOLVED -> "\u0110\u00e3 gi\u1ea3i quy\u1ebft";
                case SupportTicketStatus.CLOSED -> "\u0110\u00e3 \u0111\u00f3ng";
            };
        }
        if (status instanceof SupportPriority) {
            SupportPriority value = (SupportPriority)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case SupportPriority.LOW -> "Th\u1ea5p";
                case SupportPriority.NORMAL -> "B\u00ecnh th\u01b0\u1eddng";
                case SupportPriority.HIGH -> "Cao";
                case SupportPriority.URGENT -> "Kh\u1ea9n c\u1ea5p";
            };
        }
        if (status instanceof RiskEventStatus) {
            RiskEventStatus value = (RiskEventStatus)status;
            return switch (value) {
                default -> throw new MatchException(null, null);
                case RiskEventStatus.OPEN -> "\u0110ang m\u1edf";
                case RiskEventStatus.DISMISSED -> "\u0110\u00e3 b\u1ecf qua";
                case RiskEventStatus.CONFIRMED -> "\u0110\u00e3 x\u00e1c nh\u1eadn";
                case RiskEventStatus.RESOLVED -> "\u0110\u00e3 x\u1eed l\u00fd";
            };
        }
        if (status instanceof OrderHistoryGroup) {
            OrderHistoryGroup value = (OrderHistoryGroup)status;
            return value.label();
        }
        throw new IllegalArgumentException("No web label defined for " + status.getDeclaringClass().getName());
    }

    public static String orderStatusLabel(OrderStatus status, PaymentMethod method) {
        if (status == OrderStatus.PENDING && method == PaymentMethod.COD) {
            return "COD \u00b7 Ch\u1edd giao v\u00e0 thu ti\u1ec1n";
        }
        return StatusLabels.label(status);
    }

    public static String paymentStatusLabel(PaymentStatus status, PaymentMethod method) {
        if (status == PaymentStatus.PENDING && method == PaymentMethod.COD) {
            return "Ch\u01b0a thu ti\u1ec1n COD";
        }
        return StatusLabels.label(status);
    }

    public static String orderStatusBadgeClass(OrderStatus status) {
        if (status == null) {
            return "text-bg-secondary";
        }
        return switch (status) {
            default -> throw new MatchException(null, null);
            case OrderStatus.PENDING, OrderStatus.PAYMENT_REVIEW -> "text-bg-warning";
            case OrderStatus.PAID -> "text-bg-info";
            case OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.REFUNDED -> "text-bg-secondary";
        };
    }

    public static String paymentStatusBadgeClass(PaymentStatus status) {
        if (status == null) {
            return "text-bg-secondary";
        }
        return switch (status) {
            default -> throw new MatchException(null, null);
            case PaymentStatus.PENDING -> "text-bg-warning";
            case PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.PARTIALLY_REFUNDED -> "text-bg-info";
            case PaymentStatus.NOT_COLLECTED, PaymentStatus.REFUNDED -> "text-bg-secondary";
            case PaymentStatus.FAILED, PaymentStatus.RECONCILIATION_REQUIRED, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_FAILED -> "text-bg-danger";
        };
    }

    public static String fulfillmentStatusBadgeClass(OverallFulfillmentStatus status) {
        if (status == null) {
            return "text-bg-secondary";
        }
        return switch (status) {
            default -> throw new MatchException(null, null);
            case OverallFulfillmentStatus.DELIVERED -> "text-bg-success";
            case OverallFulfillmentStatus.DELIVERY_FAILED -> "text-bg-danger";
            case OverallFulfillmentStatus.CONFIRMED, OverallFulfillmentStatus.PROCESSING, OverallFulfillmentStatus.PICKED_UP, OverallFulfillmentStatus.IN_TRANSIT, OverallFulfillmentStatus.SHIPPING, OverallFulfillmentStatus.PARTIALLY_DELIVERED -> "text-bg-info";
            case OverallFulfillmentStatus.NOT_READY, OverallFulfillmentStatus.PICKUP_PENDING -> "text-bg-warning";
            case OverallFulfillmentStatus.PARTIALLY_DELIVERED_RETURNED, OverallFulfillmentStatus.RETURNING, OverallFulfillmentStatus.RETURNED, OverallFulfillmentStatus.CANCELLED -> "text-bg-secondary";
        };
    }

    public static String labelName(String status) {
        if (status == null || status.isBlank()) {
            return "Ch\u01b0a x\u00e1c \u0111\u1ecbnh";
        }
        return switch (status) {
            case "shipment" -> "G\u1eedi h\u00e0ng";
            case "exchange" -> "\u0110\u1ed5i h\u00e0ng";
            case "review" -> "Duy\u1ec7t y\u00eau c\u1ea7u";
            case "receive" -> "X\u00e1c nh\u1eadn \u0111\u00e3 nh\u1eadn h\u00e0ng";
            case "inspect" -> "Ki\u1ec3m tra h\u00e0ng";
            case "refund" -> "Ho\u00e0n ti\u1ec1n";
            case "retry" -> "Th\u1eed l\u1ea1i ho\u00e0n ti\u1ec1n";
            case "disputes" -> "Ph\u1ea3n h\u1ed3i tranh ch\u1ea5p";
            case "CREATED" -> "\u0110\u00e3 t\u1ea1o v\u1eadn \u0111\u01a1n";
            case "IN_TRANSIT" -> "\u0110ang v\u1eadn chuy\u1ec3n";
            case "DELIVERED" -> "\u0110\u00e3 giao";
            case "CANCELLED" -> "\u0110\u00e3 h\u1ee7y v\u1eadn chuy\u1ec3n";
            case "MANUAL_REFUND_PENDING" -> "Ch\u1edd ho\u00e0n ti\u1ec1n th\u1ee7 c\u00f4ng";
            case "cod" -> "\u0110\u1ed1i so\u00e1t COD";
            case "OPEN" -> "\u0110ang m\u1edf";
            case "AWAITING_AMOUNT" -> "Ch\u1edd x\u00e1c nh\u1eadn s\u1ed1 ti\u1ec1n";
            case "RECONCILED" -> "\u0110\u00e3 \u0111\u1ed1i so\u00e1t";
            case "NOT_COLLECTED" -> "Ch\u01b0a thu";
            case "REVIEW_REQUIRED" -> "C\u1ea7n ki\u1ec3m tra";
            case "RESOLVED" -> "\u0110\u00e3 x\u1eed l\u00fd";
            case "HIGH" -> "Cao";
            case "MEDIUM" -> "Trung b\u00ecnh";
            case "LOW" -> "Th\u1ea5p";
            case "URGENT" -> "Kh\u1ea9n c\u1ea5p";
            case "NORMAL" -> "B\u00ecnh th\u01b0\u1eddng";
            default -> status.replace('_', ' ').toLowerCase(Locale.forLanguageTag("vi-VN"));
        };
    }

    public static String badgeClass(Enum<?> status) {
        if (status == null) {
            return "text-bg-secondary";
        }
        return switch (StatusLabels.label(status)) {
            case "\u0110\u00e3 thanh to\u00e1n", "Thanh to\u00e1n th\u00e0nh c\u00f4ng", "\u0110\u00e3 giao", "\u0110\u00e3 \u0111\u0103ng", "\u0110\u00e3 duy\u1ec7t", "\u0110\u00e3 xu\u1ea5t b\u1ea3n", "S\u1eb5n s\u00e0ng", "\u0110\u00e3 ho\u00e0n t\u1ea5t" -> "text-bg-success";
            case "Ch\u1edd thanh to\u00e1n", "Ch\u1edd l\u1ea5y h\u00e0ng", "\u0110ang ch\u1edd duy\u1ec7t", "Ch\u1edd duy\u1ec7t", "Ch\u1edd qu\u1ea3n tr\u1ecb vi\u00ean duy\u1ec7t", "\u0110ang x\u00e1c minh thanh to\u00e1n" -> "text-bg-warning";
            case "B\u1ecb t\u1eeb ch\u1ed1i", "\u0110\u00e3 h\u1ee7y", "\u0110\u00e3 h\u1ebft h\u1ea1n", "\u0110\u00e3 ho\u00e0n ti\u1ec1n", "\u0110\u00e3 ho\u00e0n h\u00e0ng", "Giao th\u1ea5t b\u1ea1i", "T\u1ea1m ng\u01b0ng" -> "text-bg-danger";
            case "\u0110ang giao", "\u0110\u00e3 l\u1ea5y h\u00e0ng", "\u0110ang chu\u1ea9n b\u1ecb", "\u0110ang x\u1eed l\u00fd thanh to\u00e1n", "\u0110ang ho\u00e0n h\u00e0ng", "\u0110ang giao l\u1ea1i", "\u0110ang x\u1eed l\u00fd", "\u0110\u00e3 giao m\u1ed9t ph\u1ea7n", "\u0110\u00e3 giao m\u1ed9t ph\u1ea7n, c\u00f3 h\u00e0ng ho\u00e0n", "Ho\u00e0n t\u1ea5t m\u1ed9t ph\u1ea7n", "C\u1ea7n \u0111\u1ed1i so\u00e1t" -> "text-bg-info";
            default -> "text-bg-secondary";
        };
    }
}
