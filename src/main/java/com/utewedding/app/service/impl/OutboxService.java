package com.utewedding.app.service.impl;

import com.utewedding.app.domain.OutboxEventStatus;
import com.utewedding.app.domain.entity.OutboxEventEntity;
import com.utewedding.app.repository.OutboxEventRepository;
import com.utewedding.app.service.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final OutboxEventRepository repository;

    public OutboxService(OutboxEventRepository repository) { this.repository = repository; }

    @Transactional
    public OutboxEventEntity enqueue(String eventType, String aggregateType, UUID aggregateId,
                                     String deduplicationKey, String payload, RequestContext context) {
        return enqueueWithinTransaction(eventType, aggregateType, aggregateId, deduplicationKey, payload, context);
    }

    /** Persists a listener signal after the source transaction has already committed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxEventEntity enqueueAfterCommit(String eventType, String aggregateType, UUID aggregateId,
                                                String deduplicationKey, String payload, RequestContext context) {
        return enqueueWithinTransaction(eventType, aggregateType, aggregateId, deduplicationKey, payload, context);
    }

    private OutboxEventEntity enqueueWithinTransaction(String eventType, String aggregateType, UUID aggregateId,
                                                        String deduplicationKey, String payload, RequestContext context) {
        if (eventType == null || eventType.isBlank() || deduplicationKey == null || deduplicationKey.isBlank()) {
            throw new IllegalArgumentException("Outbox event type and deduplication key are required");
        }
        String hash = sha256(payload == null ? "" : payload);
        return repository.findByDeduplicationKey(deduplicationKey).orElseGet(() -> repository.save(
                new OutboxEventEntity(eventType, aggregateType == null ? "UNKNOWN" : aggregateType,
                        aggregateId, deduplicationKey, payload == null ? "{}" : payload, hash,
                        context == null ? null : context.requestId(), context == null ? null : context.requestId())));
    }

    @Transactional
    public List<OutboxEventEntity> claim(int limit, String owner, Instant now) {
        List<OutboxEventEntity> events = repository.findDueForClaim(
                List.of(OutboxEventStatus.PENDING.name(), OutboxEventStatus.FAILED.name()), now, Math.max(1, limit));
        Instant leaseUntil = now.plus(LEASE);
        events.forEach(event -> {
            event.setStatus(OutboxEventStatus.PROCESSING);
            event.setLeaseOwner(owner);
            event.setLeaseUntil(leaseUntil);
            event.setAttemptCount(event.getAttemptCount() + 1);
        });
        return events;
    }

    @Transactional
    public void succeed(UUID id, Instant now) {
        repository.findById(id).ifPresent(event -> {
            event.setStatus(OutboxEventStatus.SUCCEEDED); event.setProcessedAt(now); event.setLeaseUntil(null); event.setLeaseOwner(null);
        });
    }

    @Transactional
    public void fail(UUID id, String error, Instant now, boolean ambiguous) {
        repository.findById(id).ifPresent(event -> {
            event.setLastError(sanitize(error)); event.setLeaseUntil(null); event.setLeaseOwner(null);
            if (ambiguous) event.setStatus(OutboxEventStatus.AMBIGUOUS);
            else if (event.getAttemptCount() >= event.getMaxAttempts()) event.setStatus(OutboxEventStatus.DEAD);
            else { event.setStatus(OutboxEventStatus.FAILED); event.setNextAttemptAt(now.plus(backoff(event.getAttemptCount()))); }
        });
    }

    private Duration backoff(int attempt) { return Duration.ofSeconds(Math.min(3600, 30L * (1L << Math.min(6, Math.max(0, attempt - 1))))); }
    private String sanitize(String value) { if (value == null) return "unknown"; return value.replaceAll("(?i)(token|secret|password|authorization)\\s*[:=]\\s*\\S+", "$1=[REDACTED]").substring(0, Math.min(1000, value.length())); }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
