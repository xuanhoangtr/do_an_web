package com.utewedding.app.integration;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.service.port.ShippingFeePolicy;
import com.utewedding.app.service.port.ShippingProvider;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Production-capable local dispatch provider; it performs no external network calls. */
@Component
public class InternalShipperProvider implements ShippingProvider {
    private final ShippingFeePolicy fees;

    public InternalShipperProvider(ShippingFeePolicy fees) { this.fees = fees; }
    @Override public String code() { return "INTERNAL"; }
    @Override public Set<Capability> capabilities() {
        return Set.of(Capability.QUOTE, Capability.CREATE, Capability.CANCEL,
                Capability.PICKUP, Capability.TRACK, Capability.LABEL);
    }
    @Override public Quote quote(QuoteRequest request) {
        long fee = fees.calculate(request.destination(), request.merchandiseAmount());
        return new Quote("STANDARD", "INTERNAL_STANDARD", fee, "VND",
                "IQ-" + UUID.randomUUID(), Map.of("source", "INTERNAL_POLICY"),
                Instant.now().plusSeconds(900));
    }
    @Override public ShipmentResult create(CreateShipment request) {
        String tracking = trackingFor(request.idempotencyKey());
        return new ShipmentResult(tracking, tracking, tracking, "internal-label:" + tracking,
                FulfillmentStatus.PICKUP_PENDING, Map.of("mode", "INTERNAL"));
    }
    @Override public ClientRequestLookup findByClientRequestId(String operation, String clientRequestId) {
        if (!"CREATE".equals(operation)) return new ClientRequestLookup(LookupStatus.UNKNOWN, null);
        String tracking = trackingFor(clientRequestId);
        return new ClientRequestLookup(LookupStatus.FOUND,
                new ShipmentResult(tracking, tracking, tracking, "internal-label:" + tracking,
                        FulfillmentStatus.PICKUP_PENDING, Map.of("mode", "INTERNAL", "reconciled", true)));
    }
    @Override public ShipmentResult cancel(OperationRequest request) {
        return new ShipmentResult(request.providerShipmentId(), request.providerShipmentId(),
                request.trackingNumber(), null, FulfillmentStatus.CANCELLED, Map.of());
    }
    @Override public ShipmentResult requestPickup(OperationRequest request) {
        return new ShipmentResult(request.providerShipmentId(), request.providerShipmentId(),
                request.trackingNumber(), null, FulfillmentStatus.PICKUP_PENDING, Map.of());
    }
    @Override public TrackingResult track(OperationRequest request) {
        return new TrackingResult(FulfillmentStatus.PICKUP_PENDING, 0, Instant.now(), Map.of());
    }

    private static String trackingFor(String key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "INT-" + java.util.HexFormat.of().formatHex(digest).substring(0, 16).toUpperCase();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
