package com.utewedding.app.config;

import com.utewedding.app.security.FieldEncryptionKeyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

/** Initializes the AES-GCM key ring before JPA entities are persisted. */
@Configuration
public class FieldEncryptionConfig {
    private final String activeKeyId;
    private final String keys;

    public FieldEncryptionConfig(
            @Value("${security.field-encryption.active-key-id:}") String activeKeyId,
            @Value("${security.field-encryption.keys:}") String keys) {
        this.activeKeyId = activeKeyId;
        this.keys = keys;
    }

    @PostConstruct
    void initialize() {
        FieldEncryptionKeyRegistry.initialize(activeKeyId, keys);
    }
}
