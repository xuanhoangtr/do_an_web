package com.utewedding.app.security;

import com.utewedding.app.exception.EmailNotVerifiedException;
import com.utewedding.app.service.port.SessionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves RS256 Bearer JWTs and rebuilds the principal from current database state. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String EMAIL_NOT_VERIFIED_REQUEST_ATTRIBUTE =
            JwtAuthenticationFilter.class.getName() + ".emailNotVerified";

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final SessionService sessionService;
    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   CustomUserDetailsService userDetailsService,
                                   SessionService sessionService) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = resolveBearerToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = tokenProvider.validateAndGetClaims(token);
                UUID userId = UUID.fromString(claims.getSubject());
                String sessionClaim = claims.get("sid", String.class);
                UUID sessionId = UUID.fromString(sessionClaim);
                if (!sessionService.isAccessSessionActive(sessionId, userId)) {
                throw new BadCredentialsException("Bearer token session is inactive");
                }
                UserPrincipal principal = userDetailsService.loadUserById(userId.toString())
                    .withSessionId(sessionId.toString());
            if (!principal.isEmailVerified()) throw new EmailNotVerifiedException();
            Integer tokenPasswordVersion = claims.get("pwdv", Integer.class);
            if (tokenPasswordVersion == null || tokenPasswordVersion != principal.getPasswordVersion()) {
                throw new BadCredentialsException("Bearer token password version is stale");
            }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (EmailNotVerifiedException exception) {
            SecurityContextHolder.clearContext();
            request.setAttribute(EMAIL_NOT_VERIFIED_REQUEST_ATTRIBUTE, Boolean.TRUE);
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException
                 | BadCredentialsException exception) {
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
