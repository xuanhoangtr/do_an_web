package com.utewedding.app.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts Kong's X-Request-ID when it is a safe token, otherwise generates one.
 * The id is stored on the request, echoed on the response, and put in MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";
    static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:#-]{7,127}$");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = existing(request);
        if (requestId == null) {
            requestId = incoming(request);
            if (requestId == null) requestId = UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, requestId);
        }
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(new CorrelatedRequest(request, requestId), response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String existing(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof String text && SAFE_ID.matcher(text).matches()) return text;
        return null;
    }

    private static String incoming(HttpServletRequest request) {
        String candidate = first(request, HEADER, "X-Request-Id", "X-Correlation-ID");
        if (candidate == null) return null;
        String trimmed = candidate.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String first(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isRequestIdHeader(String name) {
        return HEADER.equalsIgnoreCase(name)
                || "X-Request-Id".equalsIgnoreCase(name)
                || "X-Correlation-ID".equalsIgnoreCase(name);
    }

    private static final class CorrelatedRequest extends HttpServletRequestWrapper {
        private final String requestId;

        private CorrelatedRequest(HttpServletRequest request, String requestId) {
            super(request);
            this.requestId = requestId;
        }

        @Override
        public String getHeader(String name) {
            return isRequestIdHeader(name) ? requestId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (isRequestIdHeader(name)) return Collections.enumeration(List.of(requestId));
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> existing = super.getHeaderNames();
            if (existing != null) {
                while (existing.hasMoreElements()) names.add(existing.nextElement());
            }
            names.add(HEADER);
            return Collections.enumeration(names);
        }

        @Override
        public int getIntHeader(String name) {
            return isRequestIdHeader(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public long getDateHeader(String name) {
            return isRequestIdHeader(name) ? -1 : super.getDateHeader(name);
        }
    }
}
