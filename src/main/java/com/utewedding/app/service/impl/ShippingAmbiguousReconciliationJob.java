package com.utewedding.app.service.impl;

import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.ShippingProvider;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resolves ambiguous provider outcomes without retrying until the provider says no shipment exists. */
@Component
public class ShippingAmbiguousReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(ShippingAmbiguousReconciliationJob.class);
    private final ShippingOperationPersistence persistence;
    private final ShippingProviderRegistry providers;

    public ShippingAmbiguousReconciliationJob(ShippingOperationPersistence persistence,
                                               ShippingProviderRegistry providers) {
        this.persistence = persistence;
        this.providers = providers;
    }

    @Scheduled(fixedDelayString = "${shipping.ambiguity.reconcile-delay-ms:60000}",
            initialDelayString = "${shipping.ambiguity.initial-delay-ms:60000}")
    @SchedulerLock(name = "shipping-ambiguous-reconciliation", lockAtMostFor = "10m")
    public void reconcile() {
        for (var operation : persistence.ambiguousOperations(Instant.now())) {
            try {
                var task = persistence.inspect(operation.getId());
                if (task == null) continue;
                ShippingProvider provider = providers.require(task.providerCode());
                if ("TRACK".equals(task.operation())) {
                    var tracking = provider.track(new ShippingProvider.OperationRequest(task.idempotencyKey(),
                            task.providerShipmentId(), task.trackingNumber(), task.ghnContext()));
                    persistence.succeedTracking(operation.getId(), tracking);
                    continue;
                }
                if ("CANCEL".equals(task.operation())) {
                    var tracking = provider.track(new ShippingProvider.OperationRequest(task.idempotencyKey(),
                            task.providerShipmentId(), task.trackingNumber(), task.ghnContext()));
                    if (tracking != null && tracking.status() == com.utewedding.app.domain.FulfillmentStatus.CANCELLED
                            && task.providerShipmentId().equals(String.valueOf(
                                    tracking.metadata() == null ? null : tracking.metadata().get("orderCode")))
                            && task.ghnContext() != null
                            && task.ghnContext().externalShopId().equals(String.valueOf(
                                    tracking.metadata() == null ? null : tracking.metadata().get("shopId")))) {
                        persistence.succeed(operation.getId(), new ShippingProvider.ShipmentResult(
                                task.providerShipmentId(), task.providerShipmentId(), task.trackingNumber(), null,
                                com.utewedding.app.domain.FulfillmentStatus.CANCELLED,
                                java.util.Map.of("source", "GHN_DETAIL", "cancelConfirmed", true)));
                    } else {
                        persistence.deferAmbiguous(operation.getId(),
                                "GHN Detail has not confirmed cancellation; no second cancel request was sent.");
                    }
                    continue;
                }
                ShippingProvider.ClientRequestLookup lookup = provider
                        .findByClientRequestId(task.operation(), task.idempotencyKey(), task.ghnContext());
                if (lookup == null || lookup.status() == null
                        || lookup.status() == ShippingProvider.LookupStatus.UNKNOWN) {
                    persistence.deferAmbiguous(operation.getId(),
                            "Provider lookup returned an unknown result; manual verification remains required.");
                    log.warn("Shipping operation {} remains AMBIGUOUS because provider lookup is inconclusive",
                            operation.getId());
                } else if (lookup.status() == ShippingProvider.LookupStatus.FOUND && lookup.shipment() != null) {
                    ShippingProvider.ShipmentResult result = lookup.shipment();
                    if ("CREATE".equals(task.operation()) && "GHN".equalsIgnoreCase(task.providerCode())) {
                        result = providers.require(task.providerCode()).verifyCreatedShipment(
                                new ShippingProvider.VerificationRequest(result.providerShipmentId(),
                                        task.idempotencyKey(), task.codAmount(), task.destination(),
                                        task.ghnContext()));
                    }
                    persistence.succeed(operation.getId(), result);
                } else if (lookup.status() == ShippingProvider.LookupStatus.NOT_FOUND) {
                    persistence.retryAmbiguous(operation.getId(), null,
                            new RequestContext(null, null, "shipping-ambiguity-job", "SYSTEM"));
                }
            } catch (RuntimeException exception) {
                log.error("Unable to reconcile shipping operation {}", operation.getId(), exception);
                persistence.deferAmbiguous(operation.getId(), exception.getClass().getSimpleName());
            }
        }
    }
}
