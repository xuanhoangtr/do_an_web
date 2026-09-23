package com.utewedding.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the client once, before application code can read a forwarded address. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedClientIpFilter extends OncePerRequestFilter {
    public static final String CLIENT_IP_ATTRIBUTE = TrustedClientIpFilter.class.getName() + ".clientIp";
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-port",
            "x-forwarded-proto", "x-forwarded-server", "x-forwarded-prefix");

    private final TrustedClientIpResolver resolver;

    public TrustedClientIpFilter(TrustedClientIpResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = resolver.resolve(request);
        String forwardedScheme = resolver.isTrustedProxy(request.getRemoteAddr())
                ? normalizedScheme(request.getHeader("X-Forwarded-Proto")) : null;
        request.setAttribute(CLIENT_IP_ATTRIBUTE, clientIp);
        HttpServletRequest sanitized = new HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return clientIp;
            }

            @Override
            public String getScheme() {
                return forwardedScheme == null ? super.getScheme() : forwardedScheme;
            }

            @Override
            public boolean isSecure() {
                return forwardedScheme == null ? super.isSecure() : "https".equals(forwardedScheme);
            }

            @Override
            public int getServerPort() {
                if (forwardedScheme == null) return super.getServerPort();
                return "https".equals(forwardedScheme) ? 443 : 80;
            }

            @Override
            public StringBuffer getRequestURL() {
                if (forwardedScheme == null) return super.getRequestURL();
                String serverName = super.getServerName();
                String uri = super.getRequestURI();
                return new StringBuffer(forwardedScheme).append("://").append(serverName)
                        .append(uri == null ? "" : uri);
            }

            @Override
            public String getHeader(String name) {
                return isForwardedHeader(name) ? null : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return isForwardedHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                        .filter(name -> !isForwardedHeader(name)).toList());
            }
        };
        filterChain.doFilter(sanitized, response);
    }

    private static boolean isForwardedHeader(String name) {
        return name != null && FORWARDED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    private static String normalizedScheme(String value) {
        if (value == null) return null;
        String scheme = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (scheme) {
            case "http", "https" -> scheme;
            default -> null;
        };
    }
}
