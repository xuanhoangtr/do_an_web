package com.utewedding.app.service.web;

import java.time.LocalDate;

/** A complete daily revenue bucket used by the admin dashboard. */
public record RevenuePoint(LocalDate date, long amount) {
}
