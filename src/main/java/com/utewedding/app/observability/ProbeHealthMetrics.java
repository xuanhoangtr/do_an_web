package com.utewedding.app.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/** Exports the liveness and readiness groups as 1 or 0 for Prometheus alerts. */
@Component
public class ProbeHealthMetrics {

    public ProbeHealthMetrics(MeterRegistry registry, HealthEndpoint endpoint) {
        Gauge.builder("utewedding.health.liveness", endpoint, health -> status(health, "liveness"))
                .description("1 when the liveness group is UP")
                .register(registry);
        Gauge.builder("utewedding.health.readiness", endpoint, health -> status(health, "readiness"))
                .description("1 when the readiness group is UP")
                .register(registry);
    }

    private static double status(HealthEndpoint endpoint, String path) {
        try {
            HealthComponent health = endpoint.healthForPath(path);
            return health != null && Status.UP.equals(health.getStatus()) ? 1d : 0d;
        } catch (RuntimeException ex) {
            return 0d;
        }
    }
}
