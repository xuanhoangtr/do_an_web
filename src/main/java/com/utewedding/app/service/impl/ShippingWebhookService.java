package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.entity.FulfillmentShipmentEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.ShippingEventEntity;
import com.utewedding.app.domain.entity.ShippingEventReconciliationEntity;
import com.utewedding.app.domain.entity.ShippingProviderOperationEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.FulfillmentShipmentRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.ShippingEventRepository;
import com.utewedding.app.repository.ShippingEventReconciliationRepository;
import com.utewedding.app.repository.ShippingProviderAccountRepository;
import com.utewedding.app.repository.ShippingProviderOperationRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.ShippingProvider;
import com.utewedding.app.service.port.ShippingProvider.GhnShipmentContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ShippingWebhookService {
    private final ShippingProviderRegistry providers;
    private final ShippingEventRepository events;
    private final FulfillmentShipmentRepository shipments;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRepository orders;
    private final ShippingProviderAccountRepository providerAccounts;
    private final ShippingProviderOperationRepository operations;
    private final ShippingEventReconciliationRepository reconciliations;
    private final OutboxService outbox;
    private final AuditLogPort audit;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ShippingWebhookService(ShippingProviderRegistry providers, ShippingEventRepository events,
                                  FulfillmentShipmentRepository shipments,
                                  OrderFulfillmentRepository fulfillments, OrderRepository orders,
                                  ShippingProviderAccountRepository providerAccounts,
                                  ShippingProviderOperationRepository operations,
                                  ShippingEventReconciliationRepository reconciliations,
                                  OutboxService outbox, AuditLogPort audit, ObjectMapper json,
                                  TransactionTemplate transactions) {
        this.providers = providers;
        this.events = events;
        this.shipments = shipments;
        this.fulfillments = fulfillments;
        this.orders = orders;
        this.providerAccounts = providerAccounts;
        this.operations = operations;
        this.reconciliations = reconciliations;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.transactions = transactions;
    }

    public Result receive(String providerCode, Map<String, String> headers, byte[] rawBody,
                          RequestContext context) {
        ShippingProvider provider = providers.require(providerCode);
        if (!provider.capabilities().contains(ShippingProvider.Capability.WEBHOOK)) {
            throw new IllegalArgumentException("Provider does not support webhook");
        }
        ShippingProvider.WebhookEvent parsed = provider.verifyAndParseWebhook(Map.copyOf(headers), rawBody.clone());
        String bodyHash = sha(rawBody);
        ApplyResult applied;
        try {
            applied = transactions.execute(status -> {
                ApplyResult result = apply(provider.code(), parsed, bodyHash);
                enqueueCodFollowUp(result, provider.code() + ":" + parsed.eventId(), context);
                return result;
            });
        } catch (DataIntegrityViolationException uniqueRace) {
            // Let the failed transaction fully roll back before looking up the winner.
            // PostgreSQL marks the original transaction aborted after a unique violation.
            ApplyResult duplicate = transactions.execute(status -> resolveDuplicateAfterRace(
                    provider.code(), parsed, bodyHash));
            if (duplicate == null) throw uniqueRace;
            applied = duplicate;
        }
        return new Result(parsed.eventId(), applied == null ? "REJECTED_INVALID" : applied.outcome());
    }

    private ApplyResult resolveDuplicateAfterRace(String provider, ShippingProvider.WebhookEvent event,
                                                  String bodyHash) {
        String providerShopId = text(event.providerShopId());
        ShippingEventEntity accountless = findAccountlessDuplicate(provider, providerShopId, event.eventId());
        if (accountless != null) {
            return result(bodyHash.equals(accountless.getPayloadHash()) ? "DUPLICATE" : "AMBIGUOUS_SHIPMENT");
        }
        for (FulfillmentShipmentEntity shipment : shipments.findAllByProviderCodeAndProviderShipmentId(
                provider, event.providerShipmentId())) {
            if (!hasImmutableTenantContext(shipment, providerShopId)) continue;
            UUID accountId = shipment.getProviderAccountId();
            if (accountId == null) continue;
            ShippingEventEntity accountDuplicate = findAccountScopedDuplicate(
                    provider, accountId, providerShopId, event.eventId());
            if (accountDuplicate != null) {
                return result(bodyHash.equals(accountDuplicate.getPayloadHash())
                        ? "DUPLICATE" : "AMBIGUOUS_SHIPMENT");
            }
        }
        return null;
    }

    /** Links and applies a previously authenticated GHN event only after exact tenant context validation. */
    public Result reconcileUnknownEvent(UUID eventId, UUID actorId, RequestContext context) {
        if (eventId == null || actorId == null || context == null) {
            throw new IllegalArgumentException("Thiếu thông tin quản trị viên hoặc webhook cần đối soát.");
        }
        ApplyResult applied = transactions.execute(status -> {
            ApplyResult result = reconcileUnknownEventInTransaction(eventId, actorId, context);
            enqueueCodFollowUp(result, "reconciled:" + eventId, context);
            return result;
        });
        return new Result(eventId.toString(), applied == null ? "REJECTED_INVALID" : applied.outcome());
    }

    private ApplyResult reconcileUnknownEventInTransaction(UUID eventId, UUID actorId, RequestContext requestContext) {
        ShippingEventEntity event = events.findByIdForUpdate(eventId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy webhook cần đối soát."));
        requireUnknownGhnEvent(event);
        if (reconciliations.existsByShippingEventId(eventId)) {
            throw new BusinessConflictException("Webhook GHN này đã được ghép với một vận đơn khác.");
        }

        Map<String, Object> payload = readSanitizedPayload(event);
        String providerShipmentId = text(payload.get("OrderCode"));
        String providerShopId = text(payload.get("ShopId"));
        String clientOrderCode = text(payload.get("ClientOrderCode"));
        if (providerShipmentId.isBlank() || providerShopId.isBlank() || clientOrderCode.isBlank()
                || event.getEventAt() == null) {
            recordReconciliationAudit(actorId, requestContext, event, "REJECTED_INVALID", null);
            return result("REJECTED_INVALID");
        }

        List<FulfillmentShipmentEntity> providerMatches = shipments.findAllByProviderCodeAndProviderShipmentId(
                "GHN", providerShipmentId);
        if (providerMatches.isEmpty()) {
            recordReconciliationAudit(actorId, requestContext, event, "UNKNOWN_SHIPMENT", null);
            return result("UNKNOWN_SHIPMENT");
        }
        CandidateResolution resolution = resolveCandidate(providerMatches, providerShopId,
                providerShipmentId, clientOrderCode);
        if (resolution.shipment() == null) {
            recordReconciliationAudit(actorId, requestContext, event, resolution.outcome(), null);
            return result(resolution.outcome());
        }
        FulfillmentShipmentEntity candidate = resolution.shipment();
        OrderFulfillmentEntity fulfillmentProbe = fulfillments.findById(candidate.getFulfillmentId()).orElseThrow();
        OrderEntity order = orders.findByIdForUpdate(fulfillmentProbe.getOrderId()).orElseThrow();
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(fulfillmentProbe.getId()).orElseThrow();
        FulfillmentShipmentEntity shipment = shipments.findByIdForUpdate(candidate.getId()).orElseThrow();

        // Re-read after acquiring the order -> fulfillment -> shipment locks. A GHN order code
        // can repeat across accounts, so identify the shipment again by ShopId and its CREATE receipt.
        List<FulfillmentShipmentEntity> lockedMatches = shipments
                .findAllByProviderCodeAndProviderShipmentId("GHN", providerShipmentId);
        CandidateResolution lockedResolution = resolveCandidate(lockedMatches, providerShopId,
                providerShipmentId, clientOrderCode);
        if (lockedResolution.shipment() == null
                || !lockedResolution.shipment().getId().equals(shipment.getId())) {
            String outcome = lockedResolution.shipment() == null
                    ? lockedResolution.outcome() : "AMBIGUOUS_SHIPMENT";
            recordReconciliationAudit(actorId, requestContext, event, outcome, shipment.getId());
            return result(outcome);
        }
        if (!providerShipmentId.equals(shipment.getProviderShipmentId())
                || !hasImmutableTenantContext(shipment, providerShopId)
                || !shipment.getShopId().equals(order.getShopId())
                || !resolveCreateIdentity(shipment, providerShipmentId).matches(clientOrderCode)) {
            recordReconciliationAudit(actorId, requestContext, event,
                    "REJECTED_ACCOUNT_MISMATCH", shipment.getId());
            return result("REJECTED_ACCOUNT_MISMATCH");
        }
        UUID accountId = shipment.getProviderAccountId();
        if (accountId == null || providerAccounts.findByIdAndProviderCode(accountId, "GHN").isEmpty()) {
            recordReconciliationAudit(actorId, requestContext, event,
                    "REJECTED_ACCOUNT_MISMATCH", shipment.getId());
            return result("REJECTED_ACCOUNT_MISMATCH");
        }
        ShippingEventEntity duplicate = findAccountScopedDuplicate(
                "GHN", accountId, providerShopId, event.getProviderEventId());
        if (duplicate != null && eventId.equals(duplicate.getId())) duplicate = null;
        if (duplicate != null) {
            boolean exactReplay = event.getPayloadHash().equals(duplicate.getPayloadHash())
                    && shipment.getId().equals(duplicate.getShipmentId())
                    && accountId.equals(duplicate.getProviderAccountId());
            if (exactReplay) {
                linkReconciliation(event, shipment, accountId, providerShopId, actorId);
            }
            String duplicateOutcome = exactReplay ? "DUPLICATE" : "AMBIGUOUS_SHIPMENT";
            recordReconciliationAudit(actorId, requestContext, event, duplicateOutcome, shipment.getId());
            return result(duplicateOutcome);
        }

        FulfillmentStatus canonical = parseCanonicalStatus(event.getCanonicalStatus());
        if (canonical == null) {
            recordReconciliationAudit(actorId, requestContext, event, "REJECTED_INVALID", shipment.getId());
            return result("REJECTED_INVALID");
        }
        ShippingProvider.WebhookEvent parsed = new ShippingProvider.WebhookEvent(
                event.getProviderEventId(), providerShipmentId, event.getProviderStatus(), canonical,
                event.getProviderSequence(), event.getEventAt(), payload, null, null, providerShopId);
        if (stale(shipment, parsed)) {
            linkReconciliation(event, shipment, accountId, providerShopId, actorId);
            recordReconciliationAudit(actorId, requestContext, event, "IGNORED_STALE", shipment.getId());
            return result("IGNORED_STALE");
        }
        if (!fulfillment.getStatus().canTransitionTo(canonical) && fulfillment.getStatus() != canonical) {
            recordReconciliationAudit(actorId, requestContext, event, "REJECTED_INVALID", shipment.getId());
            return result("REJECTED_INVALID");
        }

        shipment.setStatus(canonical);
        if (event.getProviderSequence() != null) shipment.setLastProviderSequence(event.getProviderSequence());
        if (event.getEventAt() != null) shipment.setLastProviderEventAt(event.getEventAt());
        fulfillment.setStatus(canonical);
        if (canonical == FulfillmentStatus.DELIVERED) {
            fulfillment.setDeliveredAt(event.getEventAt() == null ? Instant.now() : event.getEventAt());
        }
        if (canonical == FulfillmentStatus.RETURNED) {
            fulfillment.setReturnedAt(event.getEventAt() == null ? Instant.now() : event.getEventAt());
        }
        linkReconciliation(event, shipment, accountId, providerShopId, actorId);
        recordReconciliationAudit(actorId, requestContext, event, "APPLIED", shipment.getId());
        return new ApplyResult(true, "APPLIED", fulfillment.getOrderId(), fulfillment.getId(), canonical);
    }

    private CandidateResolution resolveCandidate(List<FulfillmentShipmentEntity> matches, String providerShopId,
                                                 String providerShipmentId, String clientOrderCode) {
        if (matches.isEmpty()) return new CandidateResolution(null, "UNKNOWN_SHIPMENT");
        List<FulfillmentShipmentEntity> tenantMatches = matches.stream()
                .filter(candidate -> hasImmutableTenantContext(candidate, providerShopId))
                .toList();
        if (tenantMatches.isEmpty()) return new CandidateResolution(null, "REJECTED_ACCOUNT_MISMATCH");
        if (tenantMatches.stream().map(FulfillmentShipmentEntity::getProviderAccountId).distinct().count()
                != tenantMatches.size()) {
            return new CandidateResolution(null, "AMBIGUOUS_SHIPMENT");
        }

        List<CreateIdentity> identities = tenantMatches.stream()
                .map(candidate -> resolveCreateIdentity(candidate, providerShipmentId))
                .toList();
        if (identities.stream().anyMatch(identity -> !identity.resolved())) {
            return new CandidateResolution(null, "CREATE_OPERATION_UNRESOLVED");
        }
        List<Integer> exactMatches = java.util.stream.IntStream.range(0, identities.size())
                .filter(index -> identities.get(index).matches(clientOrderCode))
                .boxed().toList();
        if (exactMatches.size() == 1) {
            return new CandidateResolution(tenantMatches.get(exactMatches.get(0)), null);
        }
        if (tenantMatches.size() == 1 && !identities.getFirst().resolved()) {
            return new CandidateResolution(null, "CREATE_OPERATION_UNRESOLVED");
        }
        return new CandidateResolution(null, exactMatches.isEmpty()
                ? "REJECTED_ACCOUNT_MISMATCH" : "AMBIGUOUS_SHIPMENT");
    }

    private CreateIdentity resolveCreateIdentity(FulfillmentShipmentEntity shipment, String providerShipmentId) {
        List<ShippingProviderOperationEntity> creates = operations
                .findAllByShipmentIdAndOperationOrderByCreatedAtAsc(shipment.getId(), "CREATE");
        List<CreateIdentity> identities = creates.stream()
                .filter(operation -> "SUCCEEDED".equals(operation.getStatus()))
                .filter(operation -> shipment.getId().equals(operation.getShipmentId())
                        && shipment.getShopId().equals(operation.getShopId()))
                .map(operation -> createIdentity(operation, providerShipmentId))
                .filter(CreateIdentity::resolved)
                .toList();
        if (identities.size() != 1) return new CreateIdentity(false, null);
        return identities.getFirst();
    }

    private CreateIdentity createIdentity(ShippingProviderOperationEntity operation, String providerShipmentId) {
        Map<String, Object> response = readJsonObject(operation.getSanitizedResponse());
        String expectedClientOrderCode = ghnClientOrderCode(operation.getIdempotencyKey());
        boolean resolved = !expectedClientOrderCode.isBlank()
                && expectedClientOrderCode.equals(text(operation.getProviderReference()))
                && providerShipmentId.equals(text(response.get("orderCode")))
                && expectedClientOrderCode.equals(text(response.get("clientOrderCode")));
        return new CreateIdentity(resolved, resolved ? expectedClientOrderCode : null);
    }

    private String ghnClientOrderCode(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "WTGHN" + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể xác định mã CREATE của GHN.", exception);
        }
    }

    private void linkReconciliation(ShippingEventEntity event, FulfillmentShipmentEntity shipment,
                                    UUID accountId, String providerShopId, UUID actorId) {
        reconciliations.saveAndFlush(new ShippingEventReconciliationEntity(event.getId(), shipment.getId(),
                accountId, shipment.getShopId(), providerShopId, actorId));
    }

    private static void requireUnknownGhnEvent(ShippingEventEntity event) {
        boolean unlinkedQueueOutcome = "UNKNOWN_SHIPMENT".equals(event.getOutcome())
                || "AMBIGUOUS_SHIPMENT".equals(event.getOutcome());
        if (!unlinkedQueueOutcome || !"GHN".equals(event.getProviderCode())
                || event.getShipmentId() != null || event.getProviderAccountId() != null) {
            throw new BusinessConflictException("Webhook này không còn ở trạng thái chờ ghép GHN.");
        }
    }

    private ApplyResult apply(String provider, ShippingProvider.WebhookEvent event, String bodyHash) {
        String providerShopId = text(event.providerShopId());
        if ("GHN".equalsIgnoreCase(provider)
                && (providerShopId.isBlank() || providerShopId.length() > 80 || event.eventAt() == null)) {
            return result("REJECTED_INVALID");
        }
        ShippingEventEntity accountlessDuplicate = findAccountlessDuplicate(provider, providerShopId, event.eventId());
        if (accountlessDuplicate != null) {
            // Exact replay is idempotent. A different receipt with the same provider event id
            // and shop scope is quarantined as ambiguous instead of silently discarded.
            return result(bodyHash.equals(accountlessDuplicate.getPayloadHash())
                    ? "DUPLICATE" : "AMBIGUOUS_SHIPMENT");
        }
        List<FulfillmentShipmentEntity> matches = shipments.findAllByProviderCodeAndProviderShipmentId(
                provider, event.providerShipmentId());
        if (matches.isEmpty()) {
            if (!providerShopId.isBlank()) {
                List<ShippingEventEntity> scopedExisting = findAccountScopedEventsForShop(
                        provider, providerShopId, event.eventId());
                if (!scopedExisting.isEmpty()) {
                    if (scopedExisting.stream().anyMatch(row -> bodyHash.equals(row.getPayloadHash()))) {
                        return result("DUPLICATE");
                    }
                    ShippingEventEntity conflict = newEvent(provider, event, bodyHash);
                    conflict.setOutcome("AMBIGUOUS_SHIPMENT");
                    conflict.setRejectionReason("same ShopId/event id has a different account-scoped receipt");
                    events.save(conflict);
                    return result("AMBIGUOUS_SHIPMENT");
                }
            }
            ShippingEventEntity unknown = newEvent(provider, event, bodyHash);
            unknown.setProviderShopId(blankToNull(providerShopId));
            unknown.setOutcome("UNKNOWN_SHIPMENT");
            events.save(unknown); // Unlinked queue row: no shop can be inferred or exposed to a Vendor.
            return result("UNKNOWN_SHIPMENT");
        }
        if (matches.size() != 1) {
            ShippingEventEntity ambiguous = newEvent(provider, event, bodyHash);
            ambiguous.setProviderShopId(blankToNull(providerShopId));
            ambiguous.setOutcome("AMBIGUOUS_SHIPMENT");
            ambiguous.setRejectionReason("multiple tenant shipments share this provider shipment identifier");
            events.save(ambiguous);
            return result("AMBIGUOUS_SHIPMENT");
        }

        FulfillmentShipmentEntity found = matches.get(0);
        UUID accountId = found.getProviderAccountId();
        boolean requireCreateIdentity = "GHN".equalsIgnoreCase(provider);
        CreateIdentity identity = requireCreateIdentity
                ? resolveCreateIdentity(found, event.providerShipmentId()) : new CreateIdentity(true, null);
        if (!identity.resolved()) {
            ShippingEventEntity unresolved = newEvent(provider, event, bodyHash);
            unresolved.setProviderShopId(blankToNull(providerShopId));
            unresolved.setOutcome("AMBIGUOUS_SHIPMENT");
            unresolved.setRejectionReason("matched shipment has no unique successful CREATE receipt");
            events.save(unresolved);
            return result("AMBIGUOUS_SHIPMENT");
        }
        if (requireCreateIdentity && !identity.matches(text(payloadValue(event, "ClientOrderCode")))) {
            ShippingEventEntity mismatch = newEvent(provider, event, bodyHash);
            mismatch.setProviderShopId(blankToNull(providerShopId));
            mismatch.setOutcome("REJECTED_ACCOUNT_MISMATCH");
            mismatch.setRejectionReason("webhook ClientOrderCode did not match the saved CREATE receipt");
            events.save(mismatch);
            return result("REJECTED_ACCOUNT_MISMATCH");
        }
        ShippingEventEntity duplicate = accountId == null ? null
                : findAccountScopedDuplicate(provider, accountId, providerShopId, event.eventId());
        if (duplicate != null) {
            return result(bodyHash.equals(duplicate.getPayloadHash()) ? "DUPLICATE" : "AMBIGUOUS_SHIPMENT");
        }
        if (!validTenantContext(found, event.providerShopId())) {
            ShippingEventEntity rejected = newEvent(provider, event, bodyHash);
            rejected.setProviderShopId(blankToNull(providerShopId));
            rejected.setProviderAccountId(accountId);
            rejected.setOutcome("REJECTED_ACCOUNT_MISMATCH");
            rejected.setRejectionReason("webhook GHN ShopId did not match immutable account/shop shipment context");
            events.save(rejected);
            return result("REJECTED_ACCOUNT_MISMATCH");
        }
        ShippingEventEntity row = newEvent(provider, event, bodyHash);
        row.setProviderShopId(blankToNull(providerShopId));
        row.setProviderAccountId(accountId);
        row.setShipmentId(found.getId());
        OrderFulfillmentEntity probe = fulfillments.findById(found.getFulfillmentId()).orElseThrow();
        orders.findByIdForUpdate(probe.getOrderId()).orElseThrow();
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(probe.getId()).orElseThrow();
        FulfillmentShipmentEntity shipment = shipments.findByIdForUpdate(found.getId()).orElseThrow();
        ShippingEventEntity lockedDuplicate = accountId == null
                ? findAccountlessDuplicate(provider, providerShopId, event.eventId())
                : findAccountScopedDuplicate(provider, accountId, providerShopId, event.eventId());
        if (lockedDuplicate != null) {
            return result(bodyHash.equals(lockedDuplicate.getPayloadHash())
                    ? "DUPLICATE" : "AMBIGUOUS_SHIPMENT");
        }
        List<FulfillmentShipmentEntity> lockedMatches = shipments.findAllByProviderCodeAndProviderShipmentId(
                provider, event.providerShipmentId());
        if (lockedMatches.size() != 1 || !lockedMatches.get(0).getId().equals(shipment.getId())) {
            row.setOutcome("AMBIGUOUS_SHIPMENT");
            row.setRejectionReason("mã vận đơn không còn duy nhất sau khi khóa bản ghi");
            events.save(row);
            return result("AMBIGUOUS_SHIPMENT");
        }
        if (!validTenantContext(shipment, event.providerShopId())) {
            row.setOutcome("REJECTED_ACCOUNT_MISMATCH");
            row.setRejectionReason("webhook GHN ShopId did not match immutable account/shop shipment context");
            events.save(row);
            return result("REJECTED_ACCOUNT_MISMATCH");
        }
        CreateIdentity lockedIdentity = requireCreateIdentity
                ? resolveCreateIdentity(shipment, event.providerShipmentId()) : new CreateIdentity(true, null);
        if (!lockedIdentity.resolved() || (requireCreateIdentity
                && !lockedIdentity.matches(text(payloadValue(event, "ClientOrderCode"))))) {
            row.setOutcome("AMBIGUOUS_SHIPMENT");
            row.setRejectionReason("webhook no longer matches a unique successful CREATE receipt");
            events.save(row);
            return result("AMBIGUOUS_SHIPMENT");
        }
        if (stale(shipment, event)) {
            row.setOutcome("IGNORED_STALE");
            row.setRejectionReason("provider sequence/event time is older than applied state");
            events.save(row);
            return result("IGNORED_STALE");
        }
        FulfillmentStatus target = event.canonicalStatus();
        if (target == null || (!fulfillment.getStatus().canTransitionTo(target)
                && fulfillment.getStatus() != target)) {
            row.setOutcome("REJECTED_INVALID");
            row.setRejectionReason("invalid canonical transition");
            events.save(row);
            return result("REJECTED_INVALID");
        }
        shipment.setStatus(target);
        if (event.sequence() != null) shipment.setLastProviderSequence(event.sequence());
        if (event.eventAt() != null) shipment.setLastProviderEventAt(event.eventAt());
        fulfillment.setStatus(target);
        if (target == FulfillmentStatus.DELIVERED) {
            fulfillment.setDeliveredAt(event.eventAt() == null ? Instant.now() : event.eventAt());
        }
        if (target == FulfillmentStatus.RETURNED) {
            fulfillment.setReturnedAt(event.eventAt() == null ? Instant.now() : event.eventAt());
        }
        row.setOutcome("APPLIED");
        row.setAppliedAt(Instant.now());
        events.saveAndFlush(row);
        audit.record("SHIPPING_EVENT_RECEIVED", null, "FulfillmentShipment", shipment.getId(),
                Map.of("outcome", "APPLIED", "status", target.name()),
                new RequestContext(null, "provider-webhook", event.eventId(), "SYSTEM"));
        return new ApplyResult(true, "APPLIED", fulfillment.getOrderId(), fulfillment.getId(), target);
    }

    private void enqueueCodFollowUp(ApplyResult applied, String sourceKey, RequestContext context) {
        if (applied == null || !applied.applied()
                || (applied.status() != FulfillmentStatus.DELIVERED
                    && applied.status() != FulfillmentStatus.RETURNED)) return;
        String deduplicationKey = "shipping-cod:webhook:" + sourceKey;
        String requestId = context != null && context.requestId() != null
                ? context.requestId() : deduplicationKey;
        outbox.enqueue("SHIPPING_COD_FULFILLMENT_UPDATE", "OrderFulfillment", applied.fulfillmentId(),
                deduplicationKey, applied.orderId() + "|" + applied.fulfillmentId() + "|" + applied.status().name(),
                new RequestContext(null, "provider-webhook", requestId, "SYSTEM"));
    }

    private boolean validTenantContext(FulfillmentShipmentEntity shipment, String providerShopId) {
        if (shipment.getProviderAccountId() == null) {
            if (shipment.getProviderContextSnapshot() != null) return false;
            if (!"GHN".equalsIgnoreCase(shipment.getProviderCode())) return true;
            return com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(shipment.getShopId())
                    && providers.require(shipment.getProviderCode()).matchesPlatformShopId(providerShopId);
        }
        return hasImmutableTenantContext(shipment, providerShopId);
    }

    private ShippingEventEntity findAccountlessDuplicate(String provider, String providerShopId, String eventId) {
        if (providerShopId == null || providerShopId.isBlank()) {
            return events.findByProviderCodeAndProviderEventIdAndProviderShopIdIsNullAndProviderAccountIdIsNull(
                    provider, eventId).orElse(null);
        }
        ShippingEventEntity scoped = events
                .findByProviderCodeAndProviderShopIdAndProviderEventIdAndProviderAccountIdIsNull(
                        provider, providerShopId.trim(), eventId)
                .orElse(null);
        if (scoped != null || !"GHN".equalsIgnoreCase(provider)) return scoped;
        // Preserve replay idempotency for append-only pre-V109 rows: their tenant ShopId
        // remains in the authenticated sanitized payload because provider_shop_id is frozen.
        return events.findLegacyGhnAccountlessByEventIdAndPayloadShopId(eventId, providerShopId.trim())
                .orElse(null);
    }

    private ShippingEventEntity findAccountScopedDuplicate(String provider, UUID accountId,
                                                            String providerShopId, String eventId) {
        if (accountId == null) return null;
        if (providerShopId == null || providerShopId.isBlank()) {
            return events.findByProviderCodeAndProviderAccountIdAndProviderShopIdIsNullAndProviderEventId(
                    provider, accountId, eventId).orElse(null);
        }
        String normalizedShopId = providerShopId.trim();
        ShippingEventEntity scoped = events
                .findByProviderCodeAndProviderAccountIdAndProviderShopIdAndProviderEventId(
                        provider, accountId, normalizedShopId, eventId)
                .orElse(null);
        if (scoped != null || !"GHN".equalsIgnoreCase(provider)) return scoped;
        return events.findLegacyGhnAccountScopedByAccountIdAndEventIdAndPayloadShopId(
                accountId, eventId, normalizedShopId).orElse(null);
    }

    private List<ShippingEventEntity> findAccountScopedEventsForShop(String provider, String providerShopId,
                                                                     String eventId) {
        String normalizedShopId = providerShopId.trim();
        List<ShippingEventEntity> scoped = events
                .findAllByProviderCodeAndProviderShopIdAndProviderEventIdAndProviderAccountIdIsNotNull(
                        provider, normalizedShopId, eventId);
        if (!"GHN".equalsIgnoreCase(provider)) return scoped;
        List<ShippingEventEntity> legacy = events.findAllLegacyGhnAccountScopedByEventIdAndPayloadShopId(
                eventId, normalizedShopId);
        return java.util.stream.Stream.concat(scoped.stream(), legacy.stream()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Object payloadValue(ShippingProvider.WebhookEvent event, String key) {
        return event.sanitizedPayload() == null ? null : event.sanitizedPayload().get(key);
    }

    private boolean hasImmutableTenantContext(FulfillmentShipmentEntity shipment, String providerShopId) {
        if (shipment.getProviderAccountId() == null) return false;
        GhnShipmentContext saved = context(shipment.getProviderContextSnapshot());
        return saved != null
                && shipment.getProviderAccountId().equals(saved.accountId())
                && shipment.getShopId().equals(saved.shopId())
                && providerShopId != null
                && providerShopId.equals(saved.externalShopId());
    }

    private Map<String, Object> readSanitizedPayload(ShippingEventEntity event) {
        return readJsonObject(event.getSanitizedPayload());
    }

    private Map<String, Object> readJsonObject(String raw) {
        try {
            Map<String, Object> payload = json.readValue(raw, new TypeReference<>() { });
            return payload == null ? Map.of() : payload;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static FulfillmentStatus parseCanonicalStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try { return FulfillmentStatus.valueOf(status); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void recordReconciliationAudit(UUID actorId, RequestContext context, ShippingEventEntity event,
                                          String outcome, UUID shipmentId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("outcome", outcome);
        metadata.put("providerCode", event.getProviderCode());
        metadata.put("providerEventId", event.getProviderEventId());
        if (shipmentId != null) metadata.put("shipmentId", shipmentId);
        audit.record("SHIPPING_EVENT_MANUAL_RECONCILE", actorId, "ShippingEvent", event.getId(),
                metadata, context);
    }

    private ShippingEventEntity newEvent(String provider, ShippingProvider.WebhookEvent event, String bodyHash) {
        ShippingEventEntity row = new ShippingEventEntity();
        row.setProviderCode(provider);
        row.setProviderEventId(required(event.eventId()));
        row.setProviderShopId(blankToNull(event.providerShopId()));
        row.setProviderStatus(required(event.providerStatus()));
        row.setCanonicalStatus(event.canonicalStatus() == null ? null : event.canonicalStatus().name());
        row.setProviderSequence(event.sequence());
        row.setEventAt(event.eventAt());
        row.setPayloadHash(bodyHash);
        row.setSanitizedPayload(sanitized(event.sanitizedPayload()));
        return row;
    }

    private GhnShipmentContext context(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return json.readValue(raw, GhnShipmentContext.class); }
        catch (Exception ignored) { return null; }
    }

    private boolean stale(FulfillmentShipmentEntity shipment, ShippingProvider.WebhookEvent event) {
        if (event.sequence() != null && shipment.getLastProviderSequence() != null
                && event.sequence() <= shipment.getLastProviderSequence()) return true;
        return event.eventAt() != null && shipment.getLastProviderEventAt() != null
                && !event.eventAt().isAfter(shipment.getLastProviderEventAt());
    }

    private String sanitized(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input != null) input.forEach((key, value) -> {
            String normalized = key.toLowerCase(Locale.ROOT);
            if (!normalized.matches(".*(secret|token|signature|authorization|phone|address|codamount).*")) {
                out.put(key, value);
            }
        });
        try { return json.writeValueAsString(out); }
        catch (Exception exception) { return "{}"; }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Invalid provider webhook");
        return value.length() > 190 ? value.substring(0, 190) : value;
    }

    private static String sha(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static ApplyResult result(String outcome) {
        return new ApplyResult(false, outcome, null, null, null);
    }

    private record ApplyResult(boolean applied, String outcome, UUID orderId, UUID fulfillmentId,
                               FulfillmentStatus status) { }
    private record CreateIdentity(boolean resolved, String clientOrderCode) {
        private boolean matches(String candidate) {
            return resolved && clientOrderCode.equals(candidate);
        }
    }
    private record CandidateResolution(FulfillmentShipmentEntity shipment, String outcome) { }
    public record Result(String eventId, String outcome) { }
}
