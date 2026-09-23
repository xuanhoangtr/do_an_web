package com.utewedding.app.security;

import java.util.Locale;

/** Runtime roles; legacy vendor tokens are read as customer tokens. */
public enum AppRole {
    USER,
    CUSTOMER,
    SHIPPER,
    MANAGER,
    ADMIN,
    SUPER_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    public static AppRole from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("User role is missing");
        }

        try {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            return "VENDOR".equals(normalized) ? CUSTOMER : valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported user role", exception);
        }
    }
}
