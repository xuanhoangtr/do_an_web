package com.utewedding.app.service.impl;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Produces short evidence notes safe for admin pages and operational logs. */
@Component
public class ShippingEvidenceRedactor {
    private static final Pattern SECRET_LABEL = Pattern.compile(
            "(?i)(token|secret|authorization|bearer|password|api[_ -]?key|shop[_ -]?id|account[_ -]?id)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9_-]{20,}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b[0-9]{5,}\\b");

    public String redact(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String safe = raw.trim().replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
        safe = URL.matcher(safe).replaceAll("[đã lược địa chỉ URL]");
        safe = SECRET_LABEL.matcher(safe).replaceAll("$1: [đã che]");
        safe = LONG_TOKEN.matcher(safe).replaceAll("[đã che mã]");
        safe = LONG_NUMBER.matcher(safe).replaceAll("[đã che số]");
        safe = safe.replaceAll("\\s+", " ").trim();
        if (safe.isBlank()) return null;
        return safe.substring(0, Math.min(safe.length(), 500));
    }

    public String maskIdentifier(String value) {
        if (value == null || value.isBlank()) return "Chưa có";
        String normalized = value.trim();
        if (normalized.length() <= 3) return "•••";
        return "••••" + normalized.substring(normalized.length() - 3);
    }
}
