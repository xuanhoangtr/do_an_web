package com.utewedding.app.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds password-reset bodies before either form binding or JSON parsing can allocate them. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PasswordResetBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 8 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    jakarta.servlet.FilterChain filterChain)
            throws ServletException, IOException {
        if (!isResetRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        filterChain.doFilter(new CachedRequest(request, body), response);
    }

    private static boolean isResetRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return false;
        String path = request.getServletPath();
        return "/reset-password".equals(path)
                || "/api/v1/auth/reset-password".equals(path)
                || "/api/auth/reset-password".equals(path);
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final Map<String, String[]> parameters;

        private CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.parameters = parseParameters(request, body);
        }

        @Override
        public int getContentLength() { return body.length; }

        @Override
        public long getContentLengthLong() { return body.length; }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] buffer, int offset, int length) {
                    return input.read(buffer, offset, length);
                }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Asynchronous reads are not supported for reset requests.");
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = parameters.get(name);
            return values == null ? null : values.clone();
        }

        @Override
        public Map<String, String[]> getParameterMap() { return parameters; }

        @Override
        public java.util.Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        private static Map<String, String[]> parseParameters(HttpServletRequest request, byte[] body) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            Charset charset = request.getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(request.getCharacterEncoding());
            parseEncoded(values, request.getQueryString(), charset);
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith("application/x-www-form-urlencoded")) {
                parseEncoded(values, new String(body, charset), charset);
            }
            Map<String, String[]> result = new LinkedHashMap<>();
            values.forEach((name, entries) -> result.put(name, entries.toArray(String[]::new)));
            return Collections.unmodifiableMap(result);
        }

        private static void parseEncoded(Map<String, List<String>> result, String encoded, Charset charset) {
            if (encoded == null || encoded.isEmpty()) return;
            for (String pair : encoded.split("&", -1)) {
                if (pair.isEmpty()) continue;
                int separator = pair.indexOf('=');
                String name = decode(separator < 0 ? pair : pair.substring(0, separator), charset);
                String value = decode(separator < 0 ? "" : pair.substring(separator + 1), charset);
                result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }

        private static String decode(String value, Charset charset) {
            return URLDecoder.decode(value, charset);
        }
    }
}
