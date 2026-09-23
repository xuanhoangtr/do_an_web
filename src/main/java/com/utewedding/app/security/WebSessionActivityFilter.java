package com.utewedding.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Updates the displayed user-activity time without counting background polling. */
public class WebSessionActivityFilter extends OncePerRequestFilter {
    private final WebSessionMetadataService metadata;

    public WebSessionActivityFilter(WebSessionMetadataService metadata) {
        this.metadata = metadata;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.equals("/api/v1/admin/attention")
                || path.equals("/api/v1/notifications/unread-count")
                || path.startsWith("/ws-utewedding/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal)
                || request.getSession(false) == null) return;
        metadata.recordActivity(request.getSession(false).getId());
    }
}
