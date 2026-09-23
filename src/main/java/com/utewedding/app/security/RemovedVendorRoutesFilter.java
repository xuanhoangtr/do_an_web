package com.utewedding.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Returns a real 404 for routes removed with the vendor operating model. */
public final class RemovedVendorRoutesFilter extends OncePerRequestFilter {
    private static final List<String> REMOVED_PREFIXES = List.of(
            "/vendor", "/seller", "/admin/vendors", "/admin/commission", "/admin/settlements",
            "/admin/finance/periods", "/admin/finance/payouts", "/admin/finance/destinations",
            "/admin/finance/holds", "/api/v1/vendor", "/api/vendor", "/api/v1/admin/vendors",
            "/api/admin/vendors", "/api/v1/admin/finance/periods", "/api/v1/admin/finance/payouts",
            "/api/v1/admin/finance/destinations", "/api/v1/admin/finance/holds",
            "/api/v1/admin/settlements", "/api/v1/admin/commission");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = applicationPath(request);
        if (REMOVED_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
    }

    @Configuration(proxyBeanMethods = false)
    static class Registration {
        @Bean
        FilterRegistrationBean<RemovedVendorRoutesFilter> removedVendorRoutesFilterRegistration() {
            FilterRegistrationBean<RemovedVendorRoutesFilter> registration =
                    new FilterRegistrationBean<>(new RemovedVendorRoutesFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }
}
