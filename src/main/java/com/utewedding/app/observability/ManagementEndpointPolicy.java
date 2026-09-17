package com.utewedding.app.observability;

import jakarta.servlet.http.HttpServletRequest;

/** Decides which Actuator requests may be anonymous, and only on the management port. */
public final class ManagementEndpointPolicy {

    private ManagementEndpointPolicy() {
    }

    public static boolean isSeparateManagementRequest(HttpServletRequest request,
                                                      int managementPort, int serverPort) {
        if (request == null || managementPort <= 0 || managementPort == serverPort) return false;
        if (request.getLocalPort() != managementPort) return false;
        String uri = path(request);
        return "/actuator".equals(uri) || uri.startsWith("/actuator/");
    }

    public static boolean isPublicProbe(HttpServletRequest request) {
        if (request == null || !isReadMethod(request.getMethod())) return false;
        String uri = path(request);
        return "/actuator/health".equals(uri)
                || uri.startsWith("/actuator/health/")
                || "/actuator/prometheus".equals(uri)
                || "/actuator/info".equals(uri);
    }

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) return "";
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (uri.isEmpty()) return "/";
        int query = uri.indexOf('?');
        return query >= 0 ? uri.substring(0, query) : uri;
    }
}
