package com.utewedding.app.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Short-lived principal cache for JWT requests; password changes explicitly evict it. */
@Component
public final class UserPrincipalCache {

    private final Cache<UUID, UserPrincipal> byId = newCache();
    private final Cache<String, UserPrincipal> byEmail = newCache();

    public UserPrincipal getById(UUID id, Function<UUID, UserPrincipal> loader) {
        return byId.get(id, loader);
    }

    public UserPrincipal getByEmail(String email, Function<String, UserPrincipal> loader) {
        return byEmail.get(email, loader);
    }

    public void invalidate(UUID id, String email) {
        if (id != null) {
            byId.invalidate(id);
        }
        if (email != null) {
            byEmail.invalidate(email);
        }
    }

    private static <K> Cache<K, UserPrincipal> newCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
    }
}
