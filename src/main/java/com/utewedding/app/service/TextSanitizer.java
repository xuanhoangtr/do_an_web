package com.utewedding.app.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import com.utewedding.app.exception.BusinessConflictException;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Central policy for user supplied text. The application renders these values
 * as text, so HTML is intentionally not accepted as a formatting language.
 */
@Component
public class TextSanitizer {

    public String plainText(String raw) {
        if (raw == null) return "";
        return Parser.unescapeEntities(Jsoup.clean(raw, Safelist.none()), false)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .strip();
    }

    /**
     * Decodes text persisted by versions that stored Jsoup's HTML-escaped output.
     * Callers must render the result in an auto-escaped text context.
     */
    public String displayText(String storedText) {
        return storedText == null ? null : Parser.unescapeEntities(storedText, false);
    }

    public String trimToNull(String raw, int maxLength) {
        String value = plainText(raw);
        if (value.isBlank()) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Returns a bounded HTTP(S) URL, or null for an absent/unsafe URL. */
    public String httpUrlOrNull(String raw, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank() || value.length() > maxLength) return null;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) return null;
            String normalized = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(normalized) && !"https".equals(normalized)) return null;
            return value;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    public String requireHttpUrlOrNull(String raw, int maxLength, String field) {
        if (raw == null || raw.isBlank()) return null;
        String value = httpUrlOrNull(raw, maxLength);
        if (value == null) {
            throw new BusinessConflictException(field + " phải là URL http hoặc https hợp lệ.");
        }
        return value;
    }
}
