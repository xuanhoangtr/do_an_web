package com.utewedding.app.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/** Rechecks saved URLs against the current filter-chain authorization. */
final class RoleAwareSavedRequestSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
    private final FilterChainProxy filters;
    private final HttpSessionRequestCache requests = SecurityConfig.htmlRequestCache();

    RoleAwareSavedRequestSuccessHandler(FilterChainProxy filters) {
        this.filters = filters;
        setRequestCache(requests);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        SavedRequest saved = requests.getRequest(request, response);
        if (saved != null && !allowed(saved, request, authentication)) requests.removeRequest(request, response);
        setDefaultTargetUrl(SecurityConfig.loginSuccessUrl(authentication));
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private boolean allowed(SavedRequest saved, HttpServletRequest request, Authentication authentication) {
        if (!"GET".equals(saved.getMethod()) || filters == null) return false;
        try {
            URI target = URI.create(saved.getRedirectUrl());
            URI origin = URI.create(request.getRequestURL().toString());
            if (!target.isAbsolute() || target.getRawUserInfo() != null || target.getFragment() != null
                    || !origin.getScheme().equalsIgnoreCase(target.getScheme())
                    || !origin.getHost().equalsIgnoreCase(target.getHost()) || port(origin) != port(target)) return false;
            String path = target.getPath();
            String context = request.getContextPath();
            if (!path.startsWith(context + "/") || !path.equals(target.getRawPath())
                    || !path.equals(target.normalize().getPath()) || path.contains("\\") || path.contains("//")) return false;
            String applicationPath = path.substring(context.length());
            if (Set.of("/login", "/logout", "/error", "/access-denied").contains(applicationPath)) return false;
            if (isStaticResource(applicationPath, saved)) return false;
            // Retain the real servlet request (port, servlet context, etc.). The standard
            // privilege evaluator's synthetic request cannot support the management-port matcher.
            var targetRequest = new HttpServletRequestWrapper(request) {
                @Override public String getMethod() { return "GET"; }
                @Override public String getRequestURI() { return path; }
                @Override public String getServletPath() { return applicationPath; }
                @Override public String getPathInfo() { return null; }
                @Override public String getQueryString() { return target.getRawQuery(); }
                @Override public StringBuffer getRequestURL() {
                    return new StringBuffer(target.getScheme() + "://" + target.getRawAuthority() + path);
                }
            };
            for (var chain : filters.getFilterChains()) {
                if (!chain.matches(targetRequest)) continue;
                for (var filter : chain.getFilters()) {
                    if (filter instanceof AuthorizationFilter authorization) {
                        var decision = authorization.getAuthorizationManager().check(() -> authentication, targetRequest);
                        return decision != null && decision.isGranted();
                    }
                }
                return false;
            }
            return false;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private static boolean isStaticResource(String path, SavedRequest saved) {
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.startsWith("/webjars/") || path.startsWith("/fonts/")
                || path.startsWith("/assets/") || path.startsWith("/favicon")) return true;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*\\.(ico|css|js|map|png|jpe?g|gif|svg|webp|woff2?|ttf|eot)$")) return true;
        return saved.getHeaderValues("Accept").stream()
                .noneMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains("text/html"));
    }
}
