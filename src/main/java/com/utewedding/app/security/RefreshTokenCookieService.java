package com.utewedding.app.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

/** Creates the refresh-token cookie without ever exposing a Domain attribute. */
@Component
public class RefreshTokenCookieService {

    private final String name;
    private final boolean secure;
    private final Duration ttl;

    public RefreshTokenCookieService(
            @Value("${security.refresh-token.cookie-name:__Host-wt_rt}") String name,
            @Value("${security.cookie.secure:true}") boolean secure,
            @Value("${security.refresh-token.ttl:30d}") Duration ttl) {
        this.name = requireHostCookieName(name);
        this.secure = secure;
        this.ttl = ttl;
        if (!secure) {
            throw new IllegalArgumentException("__Host- refresh cookie must always be Secure");
        }
    }

    public void write(HttpServletResponse response, String token, Duration maxAge) {
        if (token == null || token.isBlank()) return;
        response.addHeader("Set-Cookie", cookie(token, maxAge == null ? ttl : maxAge).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie("", Duration.ZERO).toString());
    }

    public String name() { return name; }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge == null ? ttl : maxAge)
                .build();
    }

    private static String requireHostCookieName(String value) {
        if (value == null || !value.startsWith("__Host-") || value.contains(";")) {
            throw new IllegalArgumentException("Refresh cookie name must use the __Host- prefix");
        }
        return value;
    }
}
