package com.utewedding.app.config;

import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class FormLoginAuditHandlers {
    private static final Logger LOGGER = LoggerFactory.getLogger(FormLoginAuditHandlers.class);

    private final AuditLogPort auditLog;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<org.springframework.security.web.FilterChainProxy> securityFilters;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.utewedding.app.security.WebSessionMetadataService webSessionMetadata;

    public FormLoginAuditHandlers(AuditLogPort auditLog) {
        this.auditLog = auditLog;
    }

    public AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> {
            recordSuccess(request, authentication);
            var redirect = new RoleAwareSavedRequestSuccessHandler(
                    securityFilters == null ? null : securityFilters.getIfAvailable());
            redirect.onAuthenticationSuccess(request, response, authentication);
        };
    }

    public AuthenticationFailureHandler failureHandler() {
        SimpleUrlAuthenticationFailureHandler redirect =
                new SimpleUrlAuthenticationFailureHandler("/login?error");
        return (request, response, exception) -> {
            recordFailure(request);
            redirect.onAuthenticationFailure(request, response, exception);
        };
    }

    private void recordSuccess(HttpServletRequest request, Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID actorId = principal instanceof UserPrincipal userPrincipal ? userPrincipal.getId() : null;
        if (actorId != null && webSessionMetadata != null && request.getSession(false) != null) {
            webSessionMetadata.recordLogin(request.getSession(false).getId(), actorId,
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
        }
        String actorRole = authentication == null ? null : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
        record(AuditAction.USER_LOGIN_SUCCEEDED, actorId, actorRole, request);
    }

    private void recordFailure(HttpServletRequest request) {
        record(AuditAction.USER_LOGIN_FAILED, null, null, request);
    }

    private void record(AuditAction action, UUID actorId, String actorRole, HttpServletRequest request) {
        try {
            auditLog.record(action.name(), actorId, actorId == null ? null : "User", actorId,
                    Map.of(), requestContext(request, actorRole));
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not persist form-login audit event {}", action);
        }
    }

    private RequestContext requestContext(HttpServletRequest request, String actorRole) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"), actorRole);
    }
}
