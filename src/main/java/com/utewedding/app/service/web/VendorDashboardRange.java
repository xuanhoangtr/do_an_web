package com.utewedding.app.service.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** A bounded dashboard date window resolved in the marketplace's business timezone. */
public record VendorDashboardRange(String preset, LocalDate from, LocalDate to) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static VendorDashboardRange resolve(String preset, LocalDate customFrom,
                                               LocalDate customTo, LocalDate today) {
        if (today == null) throw new IllegalArgumentException("Không xác định được ngày hiện tại.");
        String selected = preset == null || preset.isBlank() ? "last30" : preset;
        LocalDate start;
        LocalDate end = today;
        switch (selected) {
            case "today" -> start = today;
            case "last7" -> start = today.minusDays(6);
            case "last30" -> start = today.minusDays(29);
            case "month", "thisMonth" -> {
                selected = "month";
                start = today.withDayOfMonth(1);
            }
            case "year", "thisYear" -> {
                selected = "year";
                start = today.withDayOfYear(1);
            }
            case "custom" -> {
                if (customFrom == null || customTo == null) {
                    throw new IllegalArgumentException("Vui lòng chọn đủ ngày bắt đầu và kết thúc.");
                }
                if (customFrom.isAfter(customTo)) {
                    throw new IllegalArgumentException("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
                }
                if (ChronoUnit.DAYS.between(customFrom, customTo) > 365) {
                    throw new IllegalArgumentException("Khoảng ngày tùy chọn không được vượt quá 366 ngày.");
                }
                start = customFrom;
                end = customTo;
            }
            default -> throw new IllegalArgumentException("Khoảng thời gian không hợp lệ.");
        }
        return new VendorDashboardRange(selected, start, end);
    }

    public Instant fromInclusive() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    public Instant toExclusive() {
        return to.plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
