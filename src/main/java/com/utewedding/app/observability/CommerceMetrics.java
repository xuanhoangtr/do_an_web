package com.utewedding.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Low-cardinality counters for checkout, payment, refund, email, and unexpected
 * errors. A metrics failure must not change the business outcome.
 */
@Component
public class CommerceMetrics {

    private final MeterRegistry registry;
    private final boolean enabled;

    @Autowired
    public CommerceMetrics(MeterRegistry registry) {
        this(registry, true);
    }

    private CommerceMetrics(MeterRegistry registry, boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    public static CommerceMetrics noop() {
        return new CommerceMetrics(new SimpleMeterRegistry(), false);
    }

    public void orderCreated(String paymentMethod) {
        incrementAfterCommit("utewedding.orders", "result", "created",
                "payment_method", paymentMethod(paymentMethod));
    }

    public void orderReplay() {
        increment("utewedding.orders", "result", "replay", "payment_method", "none");
    }

    public void orderRejected() {
        increment("utewedding.orders", "result", "rejected", "payment_method", "none");
    }

    public void orderError() {
        increment("utewedding.orders", "result", "error", "payment_method", "none");
    }

    public void orderExpired(int count) {
        if (count <= 0) return;
        incrementAfterCommit("utewedding.orders", count, "result", "expired", "payment_method", "none");
    }

    public void payment(String provider, String result) {
        String safeResult = token(result);
        if ("succeeded".equals(safeResult) || "replay".equals(safeResult) || "started".equals(safeResult)) {
            incrementAfterCommit("utewedding.payments", "provider", provider(provider), "result", safeResult);
        } else {
            increment("utewedding.payments", "provider", provider(provider), "result", safeResult);
        }
    }

    public void refundSucceeded() {
        incrementAfterCommit("utewedding.refunds", "result", "succeeded");
    }

    public void refundReplay() {
        increment("utewedding.refunds", "result", "replay");
    }

    public void refundRejected() {
        increment("utewedding.refunds", "result", "rejected");
    }

    public void refundError() {
        increment("utewedding.refunds", "result", "error");
    }

    public void email(String kind, String result) {
        increment("utewedding.email", "kind", emailKind(kind), "result", token(result));
    }

    public void critical(String area) {
        increment("utewedding.errors", "area", token(area));
    }

    private void incrementAfterCommit(String name, String... tags) {
        incrementAfterCommit(name, 1, tags);
    }

    private void incrementAfterCommit(String name, double amount, String... tags) {
        if (!enabled) return;
        Runnable record = () -> increment(name, amount, tags);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record.run();
            }
        });
    }

    private void increment(String name, String... tags) {
        increment(name, 1, tags);
    }

    private void increment(String name, double amount, String... tags) {
        if (!enabled || amount <= 0) return;
        try {
            Counter.Builder builder = Counter.builder(name);
            for (int index = 0; index + 1 < tags.length; index += 2) {
                builder.tag(tags[index], tags[index + 1]);
            }
            builder.register(registry).increment(amount);
        } catch (RuntimeException ignored) {
            // Checkout, payment, and mail must succeed even when the registry misbehaves.
        }
    }

    static String paymentMethod(String value) {
        String token = token(value);
        return switch (token) {
            case "cod", "vnpay", "momo" -> token;
            default -> "other";
        };
    }

    static String provider(String value) {
        String token = token(value);
        return switch (token) {
            case "vnpay", "momo", "cod", "manual", "manual_refund" -> token;
            default -> "other";
        };
    }

    static String emailKind(String value) {
        String token = token(value);
        return switch (token) {
            case "verification", "password_reset", "order" -> token;
            default -> "other";
        };
    }

    static String token(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[a-z0-9_]{1,32}")) return "other";
        return normalized;
    }
}
