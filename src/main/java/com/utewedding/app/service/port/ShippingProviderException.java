package com.utewedding.app.service.port;

import java.util.Map;

/** Provider response with a safe diagnostic snapshot and a classified outcome. */
public final class ShippingProviderException extends RuntimeException {
    public enum Outcome { REJECTED, UNKNOWN }

    private final Outcome outcome;
    private final Map<String, Object> diagnostics;
    private final boolean notFound;

    public ShippingProviderException(String message, Outcome outcome, Map<String, Object> diagnostics,
                                     boolean notFound) {
        super(message);
        this.outcome = outcome == null ? Outcome.UNKNOWN : outcome;
        this.diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        this.notFound = notFound;
    }

    public Outcome outcome() { return outcome; }
    public Map<String, Object> diagnostics() { return diagnostics; }
    public boolean notFound() { return notFound; }
}
