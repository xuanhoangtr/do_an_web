package com.utewedding.app.service.web;

import java.time.Instant;

/** Seller-safe history row; deliberately has no bank, proof, admin note, or actor fields. */
public record VendorFinanceHistoryRow(String kind, String label, String status, long amount,
                                      Instant occurredAt, String reference) { }
