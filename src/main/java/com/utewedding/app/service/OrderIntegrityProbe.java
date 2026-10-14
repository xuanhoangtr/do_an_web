package com.utewedding.app.service;

/**
 * Optional seam that lets concurrency tests pause inside an open transaction.
 * Production does not register an implementation.
 */
public interface OrderIntegrityProbe {
    default void beforeIdempotencyClaim() {
    }

    default void afterInventoryReserved() {
    }

    default void beforeCompetingStatusWrite(String action) {
    }
}
