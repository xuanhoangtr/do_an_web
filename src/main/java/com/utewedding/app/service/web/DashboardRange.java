package com.utewedding.app.service.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public record DashboardRange(String preset, LocalDate from, LocalDate to) {
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static DashboardRange resolve(String preset, LocalDate customFrom, LocalDate customTo,
                                         LocalDate today) {
        String selectedPreset = preset == null || preset.isBlank() ? "last7" : preset;
        LocalDate start;
        LocalDate end = today;
        switch (selectedPreset) {
            case "today" -> start = today;
            case "last7" -> start = today.minusDays(6);
            case "last30" -> start = today.minusDays(29);
            case "thisMonth" -> start = today.withDayOfMonth(1);
            case "custom" -> {
                if (customFrom == null || customTo == null) {
                    throw new IllegalArgumentException("Vui lòng chọn đầy đủ ngày bắt đầu và kết thúc.");
                }
                if (customFrom.isAfter(customTo)) {
                    throw new IllegalArgumentException("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
                }
                start = customFrom;
                end = customTo;
            }
            default -> throw new IllegalArgumentException("Khoảng thời gian không hợp lệ.");
        }
        return new DashboardRange(selectedPreset, start, end);
    }

    public LocalDate previousFrom() {
        if ("thisMonth".equals(preset)) {
            return from.minusMonths(1);
        }
        return previousTo().minusDays(dayCount() - 1);
    }

    public LocalDate previousTo() {
        if ("thisMonth".equals(preset)) {
            return to.minusMonths(1);
        }
        return from.minusDays(1);
    }

    public long dayCount() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public Instant fromInstant(ZoneId zone) {
        return from.atStartOfDay(zone).toInstant();
    }

    public Instant toExclusiveInstant(ZoneId zone) {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    public String label() {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return from.equals(to) ? from.format(format) : from.format(format) + " – " + to.format(format);
    }
}
