package com.utewedding.app.service.port;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One-way digest for opaque tokens; raw tokens are never persisted. */
public final class SecureHash {
    private SecureHash() {
    }

    public static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Value to hash must not be null");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
