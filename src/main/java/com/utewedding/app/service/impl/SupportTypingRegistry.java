package com.utewedding.app.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Short-lived typing indicators; presence is intentionally never persisted. */
@Component
public class SupportTypingRegistry {
    private static final Duration TTL = Duration.ofSeconds(10);
    private final ConcurrentMap<Key, Value> active = new ConcurrentHashMap<>();

    public void mark(UUID ticketId, UUID userId, String name) {
        active.put(new Key(ticketId, userId), new Value(name, Instant.now().plus(TTL)));
    }

    public void clear(UUID ticketId, UUID userId) { active.remove(new Key(ticketId, userId)); }

    public List<SupportChatService.TypingUser> active(UUID ticketId, UUID excludedUserId) {
        Instant now = Instant.now();
        active.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        return active.entrySet().stream()
                .filter(entry -> entry.getKey().ticketId().equals(ticketId)
                        && !entry.getKey().userId().equals(excludedUserId))
                .map(entry -> new SupportChatService.TypingUser(entry.getKey().userId(), entry.getValue().name()))
                .toList();
    }

    private record Key(UUID ticketId, UUID userId) { }
    private record Value(String name, Instant expiresAt) { }
}
