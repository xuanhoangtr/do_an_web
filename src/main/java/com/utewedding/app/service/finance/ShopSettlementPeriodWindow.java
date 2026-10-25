package com.utewedding.app.service.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;

/** A Monday-to-Monday, half-open weekly period in Vietnam local time. */
public record ShopSettlementPeriodWindow(LocalDate weekStart) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static ShopSettlementPeriodWindow containing(Instant instant) {
        if (instant == null) throw new IllegalArgumentException("instant is required");
        LocalDate monday = instant.atZone(ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new ShopSettlementPeriodWindow(monday);
    }

    public Instant startInclusive() {
        return weekStart.atStartOfDay(ZONE).toInstant();
    }

    public Instant endExclusive() {
        return weekStart.plusDays(7).atStartOfDay(ZONE).toInstant();
    }

    public ShopSettlementPeriodWindow next() {
        return new ShopSettlementPeriodWindow(weekStart.plusDays(7));
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(startInclusive()) && instant.isBefore(endExclusive());
    }

    public static boolean returnWindowElapsed(Instant lastDelivery, int snapshotDays, Instant now) {
        return lastDelivery != null && snapshotDays >= 0 && now != null
                && !now.isBefore(lastDelivery.plusSeconds(Math.multiplyExact(snapshotDays, 86_400L)));
    }
}
