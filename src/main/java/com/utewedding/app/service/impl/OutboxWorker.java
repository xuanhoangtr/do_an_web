package com.utewedding.app.service.impl;

import com.utewedding.app.exception.AmbiguousOutboxException;
import com.utewedding.app.service.port.OutboxDispatcher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
public class OutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxService outbox;
    private final ObjectProvider<OutboxDispatcher> dispatchers;
    private final String owner = "outbox-" + UUID.randomUUID();

    public OutboxWorker(OutboxService outbox, ObjectProvider<OutboxDispatcher> dispatchers) {
        this.outbox = outbox; this.dispatchers = dispatchers;
    }

    @Scheduled(fixedDelayString = "${outbox.worker.fixed-delay-ms:5000}")
    @SchedulerLock(name = "outbox-worker", lockAtMostFor = "5m")
    public void process() {
        for (var event : outbox.claim(20, owner, Instant.now())) {
            try {
                OutboxDispatcher dispatcher = dispatchers.orderedStream().filter(item -> item.supports(event.getEventType())).findFirst().orElseThrow(() -> new IllegalStateException("No outbox dispatcher for " + event.getEventType()));
                dispatcher.dispatch(event);
                outbox.succeed(event.getId(), Instant.now());
            } catch (AmbiguousOutboxException exception) {
                outbox.fail(event.getId(), exception.getMessage(), Instant.now(), true);
            } catch (RuntimeException exception) {
                LOG.warn("Outbox event failed type={} id={}", event.getEventType(), event.getId(), exception);
                outbox.fail(event.getId(), exception.getMessage(), Instant.now(), false);
            }
        }
    }
}