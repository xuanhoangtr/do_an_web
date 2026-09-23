package com.utewedding.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-process fixed-window limiter for public and API abuse protection. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);

    private ObjectMapper objectMapper;
    private TrustedClientIpResolver clientIpResolver;
    private Cache<String, Window> windows;
    private Policy global;
    private Policy login;
    private Policy register;
    private Policy refresh;
    private Policy forgotPassword;
    private Policy resetPassword;
    private Policy wish;
    private Policy upload;
    private Policy rsvp;
    private Policy publicEndpoint;
    private Policy slugCheck;
    private Policy guestLink;

    /** Compatibility constructor for focused tests using the original policies. */
    public RateLimitFilter(ObjectMapper objectMapper,
                           long globalTtl, long globalLimit,
                           long loginTtl, long loginLimit,
                           long registerTtl, long registerLimit,
                           long refreshTtl, long refreshLimit) {
        init(objectMapper, 10_000, 3_600,
                globalTtl, globalLimit, loginTtl, loginLimit,
                registerTtl, registerLimit, refreshTtl, refreshLimit,
                3600, 5, 3600, 5, 3600, 5, 3600, 60,
                3600, 10, 60, 100, 60, 30, 60, 30);
        this.clientIpResolver = TrustedClientIpResolver.loopbackOnly();
    }

    @Autowired
    public RateLimitFilter(
            ObjectMapper objectMapper,
            @Value("${rate-limit.cache.maximum-size:10000}") long maximumSize,
            @Value("${rate-limit.cache.expire-after-access-seconds:3600}") long cacheExpirySeconds,
            @Value("${rate-limit.global.ttl:60}") long globalTtl,
            @Value("${rate-limit.global.limit:120}") long globalLimit,
            @Value("${rate-limit.login.ttl:300}") long loginTtl,
            @Value("${rate-limit.login.limit:5}") long loginLimit,
            @Value("${rate-limit.register.ttl:3600}") long registerTtl,
            @Value("${rate-limit.register.limit:5}") long registerLimit,
            @Value("${rate-limit.refresh.ttl:60}") long refreshTtl,
            @Value("${rate-limit.refresh.limit:30}") long refreshLimit,
            @Value("${rate-limit.forgot-password.ttl:3600}") long forgotTtl,
            @Value("${rate-limit.forgot-password.limit:5}") long forgotLimit,
            @Value("${rate-limit.reset-password.ttl:3600}") long resetTtl,
            @Value("${rate-limit.reset-password.limit:5}") long resetLimit,
            @Value("${rate-limit.wish.ttl:3600}") long wishTtl,
            @Value("${rate-limit.wish.limit:5}") long wishLimit,
            @Value("${rate-limit.upload.ttl:3600}") long uploadTtl,
            @Value("${rate-limit.upload.limit:60}") long uploadLimit,
            @Value("${rate-limit.rsvp.ttl:3600}") long rsvpTtl,
            @Value("${rate-limit.rsvp.limit:10}") long rsvpLimit,
            @Value("${rate-limit.public.ttl:60}") long publicTtl,
            @Value("${rate-limit.public.limit:100}") long publicLimit,
            @Value("${rate-limit.slug-check.ttl:60}") long slugTtl,
            @Value("${rate-limit.slug-check.limit:30}") long slugLimit,
            @Value("${rate-limit.guest-link.ttl:60}") long guestTtl,
            @Value("${rate-limit.guest-link.limit:30}") long guestLimit,
            ObjectProvider<TrustedClientIpResolver> clientIpResolverProvider) {
        this.clientIpResolver = clientIpResolverProvider.getIfAvailable(TrustedClientIpResolver::loopbackOnly);
        init(objectMapper, maximumSize, cacheExpirySeconds,
                globalTtl, globalLimit, loginTtl, loginLimit,
                registerTtl, registerLimit, refreshTtl, refreshLimit,
                forgotTtl, forgotLimit, resetTtl, resetLimit,
                wishTtl, wishLimit, uploadTtl, uploadLimit,
                rsvpTtl, rsvpLimit, publicTtl, publicLimit,
                 slugTtl, slugLimit, guestTtl, guestLimit);
    }

    private RateLimitFilter(
            ObjectMapper objectMapper,
            long maximumSize, long cacheExpirySeconds,
            long globalTtl, long globalLimit, long loginTtl, long loginLimit,
            long registerTtl, long registerLimit, long refreshTtl, long refreshLimit,
            long forgotTtl, long forgotLimit, long resetTtl, long resetLimit,
            long wishTtl, long wishLimit, long uploadTtl, long uploadLimit,
            long rsvpTtl, long rsvpLimit, long publicTtl, long publicLimit,
            long slugTtl, long slugLimit, long guestTtl, long guestLimit,
            TrustedClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
        init(objectMapper, maximumSize, cacheExpirySeconds,
                globalTtl, globalLimit, loginTtl, loginLimit,
                registerTtl, registerLimit, refreshTtl, refreshLimit,
                forgotTtl, forgotLimit, resetTtl, resetLimit,
                wishTtl, wishLimit, uploadTtl, uploadLimit,
                rsvpTtl, rsvpLimit, publicTtl, publicLimit,
                slugTtl, slugLimit, guestTtl, guestLimit);
    }

    /** Compatibility constructor for tests and local callers without a proxy resolver. */
    public RateLimitFilter(
            ObjectMapper objectMapper,
            long maximumSize, long cacheExpirySeconds,
            long globalTtl, long globalLimit,
            long loginTtl, long loginLimit,
            long registerTtl, long registerLimit,
            long refreshTtl, long refreshLimit,
            long forgotTtl, long forgotLimit,
            long resetTtl, long resetLimit,
            long wishTtl, long wishLimit,
            long uploadTtl, long uploadLimit,
            long rsvpTtl, long rsvpLimit,
            long publicTtl, long publicLimit,
            long slugTtl, long slugLimit,
            long guestTtl, long guestLimit) {
        this(objectMapper, maximumSize, cacheExpirySeconds,
                globalTtl, globalLimit, loginTtl, loginLimit,
                registerTtl, registerLimit, refreshTtl, refreshLimit,
                forgotTtl, forgotLimit, resetTtl, resetLimit,
                wishTtl, wishLimit, uploadTtl, uploadLimit,
                rsvpTtl, rsvpLimit, publicTtl, publicLimit,
                slugTtl, slugLimit, guestTtl, guestLimit,
                TrustedClientIpResolver.loopbackOnly());
    }

    private void init(ObjectMapper mapper, long maximumSize, long cacheExpirySeconds,
                      long globalTtl, long globalLimit, long loginTtl, long loginLimit,
                      long registerTtl, long registerLimit, long refreshTtl, long refreshLimit,
                      long forgotTtl, long forgotLimit, long resetTtl, long resetLimit,
                      long wishTtl, long wishLimit, long uploadTtl, long uploadLimit,
                      long rsvpTtl, long rsvpLimit, long publicTtl, long publicLimit,
                      long slugTtl, long slugLimit, long guestTtl, long guestLimit) {
        this.objectMapper = mapper;
        this.global = Policy.seconds(globalTtl, globalLimit, "global");
        this.login = Policy.seconds(loginTtl, loginLimit, "login");
        this.register = Policy.seconds(registerTtl, registerLimit, "register");
        this.refresh = Policy.seconds(refreshTtl, refreshLimit, "refresh");
        this.forgotPassword = Policy.seconds(forgotTtl, forgotLimit, "forgot-password");
        this.resetPassword = Policy.seconds(resetTtl, resetLimit, "reset-password");
        this.wish = Policy.seconds(wishTtl, wishLimit, "wish");
        this.upload = Policy.seconds(uploadTtl, uploadLimit, "upload");
        this.rsvp = Policy.seconds(rsvpTtl, rsvpLimit, "rsvp");
        this.publicEndpoint = Policy.seconds(publicTtl, publicLimit, "public");
        this.slugCheck = Policy.seconds(slugTtl, slugLimit, "slug-check");
        this.guestLink = Policy.seconds(guestTtl, guestLimit, "guest-link");
        this.windows = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maximumSize))
                .expireAfterAccess(Math.max(1, cacheExpirySeconds), TimeUnit.SECONDS)
                .removalListener((String key, Window value,
                                   com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        LOGGER.debug("Rate-limit window evicted: key={}, cause={}", key, cause);
                    }
                })
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ERROR) return true;
        String path = path(request);
        boolean webAuthPost = "POST".equalsIgnoreCase(request.getMethod())
                && ("/login".equals(path) || "/register".equals(path)
                || "/verify-email".equals(path) || "/verify-email/resend".equals(path)
                || "/forgot-password".equals(path) || "/reset-password".equals(path)
                || "/profile/privacy/export".equals(path));
        return !(path.startsWith("/api/")
                || webAuthPost
                || isPublicWeddingPath(path)
                || path.endsWith("/wish")
                || path.endsWith("/rsvp"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Policy policy = policyFor(request);
        Window window = windows.get(policyKey(request, policy), ignored -> new Window());
        long retryAfter = window.tryConsume(policy);
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setHeader("Cache-Control", "no-store");
            if (prefersHtml(request)) {
                request.setAttribute("rateLimitRetryAfter", retryAfter + " giây");
                response.sendError(429);
            } else {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getWriter(), Map.of(
                        "status", 429,
                        "error", "Too Many Requests",
                        "message", "Quá nhiều yêu cầu. Vui lòng thử lại sau.",
                        "timestamp", Instant.now().toString()));
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Policy policyFor(HttpServletRequest request) {
        String path = path(request);
        if ("/orders/track".equals(path) || path.startsWith("/orders/track/")) return publicEndpoint;
        if ("/login".equals(path)) return login;
        if ("/register".equals(path) || "/verify-email".equals(path)
                || "/verify-email/resend".equals(path)) return register;
        if (path.endsWith("/auth/login")) return login;
        if (path.endsWith("/auth/register")) return register;
        if (path.endsWith("/auth/refresh")) return refresh;
        if (path.endsWith("/auth/forgot-password") || "/forgot-password".equals(path)) return forgotPassword;
        if (path.endsWith("/auth/reset-password") || "/reset-password".equals(path)) return resetPassword;
        if (path.contains("slug") && path.contains("check")) return slugCheck;
        if (path.contains("guest") && (path.contains("link") || path.contains("token"))) return guestLink;
        if (path.endsWith("/media/upload")) return upload;
        if (path.endsWith("/rsvp")) return rsvp;
        if (path.endsWith("/wishes") || path.endsWith("/wish")) return wish;
        if (isGuestLinkPath(path)) return guestLink;
        if (isPublicWeddingPath(path)) {
            return request.getParameter("token") == null ? publicEndpoint : guestLink;
        }
        if (path.startsWith("/api/public/")) return publicEndpoint;
        return global;
    }

    private boolean isPublicWeddingPath(String path) {
        return path.startsWith("/w/") || path.startsWith("/wedding/view/")
                || isGuestLinkPath(path);
    }

    private boolean isGuestLinkPath(String path) {
        return path.matches("/[^/]+/k/[^/]+");
    }

    private String policyKey(HttpServletRequest request, Policy policy) {
        return clientIpResolver.resolve(request) + "|" + policy.name();
    }

    private boolean prefersHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) return false;
        double html = quality(accept, "text/html");
        double json = quality(accept, "application/json");
        return html > 0 && html >= json;
    }

    private double quality(String accept, String mediaType) {
        double best = 0;
        for (String part : accept.split(",")) {
            String[] pieces = part.trim().split(";\\s*");
            if (!mediaType.equalsIgnoreCase(pieces[0].trim())) continue;
            double quality = 1;
            for (int i = 1; i < pieces.length; i++) {
                String parameter = pieces[i].trim();
                if (parameter.startsWith("q=")) {
                    try { quality = Double.parseDouble(parameter.substring(2)); }
                    catch (NumberFormatException ignored) { quality = 0; }
                }
            }
            best = Math.max(best, quality);
        }
        return best;
    }

    private String path(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        if (contextPath == null || contextPath.isEmpty()) return uri;
        return uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
    }

    long cacheSize() {
        windows.cleanUp();
        return windows.estimatedSize();
    }

    private record Policy(long ttlMillis, long limit, String name) {
        private static Policy seconds(long ttlSeconds, long limit, String name) {
            return new Policy(Math.max(1, ttlSeconds) * 1000L, Math.max(1, limit), name);
        }
    }

    private static final class Window {
        private long startedAt = System.currentTimeMillis();
        private long count;

        synchronized long tryConsume(Policy policy) {
            long now = System.currentTimeMillis();
            if (now - startedAt >= policy.ttlMillis()) {
                startedAt = now;
                count = 0;
            }
            if (count >= policy.limit()) {
                return Math.max(1, (policy.ttlMillis() - (now - startedAt) + 999) / 1000);
            }
            count++;
            return 0;
        }
    }
}
