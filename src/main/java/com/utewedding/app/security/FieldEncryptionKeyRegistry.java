package com.utewedding.app.security;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-process key ring used by the AES-256-GCM JPA converter. */
public final class FieldEncryptionKeyRegistry {
    private static volatile KeyRing ring;

    private FieldEncryptionKeyRegistry() { }

    public static void initialize(String activeKeyId, String configuredKeys) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (configuredKeys != null && !configuredKeys.isBlank()) {
            for (String entry : configuredKeys.split(",")) {
                int separator = entry.indexOf('=');
                if (separator <= 0) throw new IllegalArgumentException("Field encryption keys must use keyId=base64 entries");
                byte[] key = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
                if (key.length != 32) throw new IllegalArgumentException("AES field encryption keys must be 256 bits");
                keys.put(entry.substring(0, separator).trim(), key);
            }
        }
        if (activeKeyId == null || activeKeyId.isBlank() || !keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("Field encryption active key is missing or not in the key ring");
        }
        ring = new KeyRing(activeKeyId, Map.copyOf(keys));
    }

    public static KeyRing current() {
        KeyRing value = ring;
        if (value == null) throw new IllegalStateException("Field encryption key ring is not initialized");
        return value;
    }

    public record KeyRing(String activeKeyId, Map<String, byte[]> keys) { }
}
