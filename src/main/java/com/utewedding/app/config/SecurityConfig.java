package com.utewedding.app.config;

import com.utewedding.app.observability.ManagementEndpointPolicy;
import com.utewedding.app.security.WebSessionActivityFilter;
import com.utewedding.app.security.ApiAuthenticationEntryPoint;
import com.utewedding.app.security.CustomUserDetailsService;
import com.utewedding.app.security.JwtAuthenticationFilter;
import com.utewedding.app.security.MigratingPasswordEncoder;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Security boundary for the migration.
 *
 * API requests are stateless and use RS256 Bearer JWTs. Thymeleaf requests keep the
 * normal Spring Security session cookie and form login. CSRF is never disabled:
 * only API requests that prove they use an Authorization header bypass the
 * cookie-based CSRF check; browser form and refresh-cookie flows remain protected.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.environment:development}")
    private String environment;

    /**
     * Actuator is served on the management port. Anonymous access is limited to
     * health, info, and Prometheus. The public port denies every actuator path.
     */
    @Bean
    @org.springframework.core.annotation.Order(0)
    SecurityFilterChain managementSecurityFilterChain(
            HttpSecurity http,
            @Value("${management.server.port:0}") int managementPort,
            @Value("${server.port:8080}") int serverPort) throws Exception {
        http.securityMatcher(request -> ManagementEndpointPolicy.isSeparateManagementRequest(
                        request, managementPort, serverPort))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ManagementEndpointPolicy::isPublicProbe).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain adminAttentionSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
                // The header polls this one read-only endpoint with the form-login cookie.
                // Keep the rest of /api/** stateless and JWT-only.
                .securityMatcher("/api/v1/admin/attention", "/api/v1/admin/attention/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .headers(headers -> headers
                        .addHeaderWriter(new StaticHeadersWriter("Strict-Transport-Security", "max-age=31536000"))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(cspPolicy()))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(policy -> policy.policy("geolocation=(), microphone=(), camera=()")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasAnyRole("ADMIN", "MANAGER", "SUPER_ADMIN"))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            @Value("${security.refresh-token.cookie-name:__Host-wt_rt}") String refreshCookieName) throws Exception {
        http
                .securityMatcher("/api/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(publicWishRequestMatcher(), paymentPostCallbackRequestMatcher(),
                                shippingWebhookRequestMatcher(), bearerOnlyApiRequestMatcher(refreshCookieName)))
                .headers(headers -> headers
                        .addHeaderWriter(new StaticHeadersWriter("Strict-Transport-Security", "max-age=31536000"))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(cspPolicy()))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(policy -> policy.policy("geolocation=(), microphone=(), camera=()")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/api/auth/login",
                                "/api/v1/auth/csrf", "/api/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/auth/register",
                                "/api/v1/auth/refresh", "/api/auth/refresh",
                                "/api/v1/auth/logout", "/api/auth/logout", "/api/public/**").permitAll()
                        .requestMatchers("/api/v1/auth/verify-email", "/api/auth/verify-email",
                                "/api/v1/auth/resend-verification", "/api/auth/resend-verification")
                        .permitAll()
                        .requestMatchers("/api/v1/auth/forgot-password", "/api/auth/forgot-password",
                                "/api/v1/auth/reset-password", "/api/auth/reset-password").permitAll()
                        // Public guest wish submission and provider callbacks do not carry a user JWT.
                        .requestMatchers(HttpMethod.POST, "/api/v1/weddings/*/wishes").permitAll()
                        .requestMatchers("/api/v1/payments/vnpay/ipn", "/api/v1/payments/vnpay/vnpay_ipn",
                                "/api/v1/payments/vnpay/return", "/api/v1/payments/momo/ipn",
                                "/api/v1/payments/momo/return").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/shipping/providers/*/webhook").permitAll()
                        .requestMatchers("/api/v1/provinces", "/api/v1/provinces/**").permitAll()
                        // Feature flags do not replace authorization. Until shop membership
                        // and participant checks are implemented, keep private marketplace
                        // operations closed even when their rollout flags are enabled.
                        .requestMatchers("/api/v1/vendor/finance/payout-destinations",
                                "/api/v1/vendor/finance/payout-destinations/**",
                                "/api/v1/vendor/finance/payouts",
                                "/api/v1/vendor/finance/payouts/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers("/api/v1/shops/*/chat", "/api/v1/shops/*/chat/**",
                                "/api/v1/vendor", "/api/v1/vendor/**", "/api/vendor", "/api/vendor/**",
                                "/api/v1/shipping/shop-quotes", "/api/shipping/shop-quotes")
                        .denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/shops", "/api/v1/shops/**").permitAll()
                        .requestMatchers("/api/v1/shops/**").denyAll()
                        .requestMatchers("/api/v1/admin/vendors", "/api/v1/admin/vendors/**",
                                "/api/v1/admin/commission", "/api/v1/admin/commission/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/returns/*/refund", "/api/v1/admin/returns/*/retry").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/returns", "/api/v1/admin/returns/**").hasAnyRole("MANAGER", "ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/returns", "/api/v1/returns/**").hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers("/api/v1/orders/*/refund")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/fulfillments", "/api/v1/admin/fulfillments/**",
                                "/api/v1/admin/shippers", "/api/v1/admin/categories",
                                "/api/v1/admin/categories/**", "/api/v1/admin/vouchers",
                                "/api/v1/admin/vouchers/**")
                        .hasAnyRole("ADMIN", "MANAGER", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/support", "/api/v1/admin/support/**")
                        .hasAnyRole("ADMIN", "MANAGER", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/risk-events", "/api/v1/admin/risk-events/**")
                        .hasAnyRole("ADMIN", "MANAGER", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/finance", "/api/v1/admin/finance/**")
                        .hasAnyRole("ADMIN", "MANAGER", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin", "/api/v1/admin/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/shipper/**").hasRole("SHIPPER")
                        .requestMatchers("/api/v1/cart", "/api/v1/cart/**", "/api/cart", "/api/cart/**",
                                "/api/v1/orders", "/api/v1/orders/**", "/api/orders", "/api/orders/**",
                                "/api/v1/weddings", "/api/v1/weddings/**", "/api/v1/customer/**",
                                "/api/v1/media/**",
                                "/api/customer/**", "/api/v1/products/*/favorite",
                                "/api/v1/reviews/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers("/api/v1/support", "/api/v1/support/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/*/reviews")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/products/*/reviews/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/vnpay/create",
                                "/api/v1/payments/vnpay/create-payment",
                                "/api/v1/payments/momo/create/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/user/**").hasAnyRole(
                                "USER", "CUSTOMER", "SHIPPER", "MANAGER", "ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/**").authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(3)
    SecurityFilterChain webSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SessionRegistry sessionRegistry,
            FormLoginAuditHandlers formLoginAuditHandlers,
            WebSessionActivityFilter webSessionActivityFilter) throws Exception {
        http
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.requestCache(htmlRequestCache()))
                .headers(headers -> headers
                        .addHeaderWriter(new StaticHeadersWriter("Strict-Transport-Security", "max-age=31536000"))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(cspPolicy()))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(policy -> policy.policy("geolocation=(), microphone=(), camera=()")))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sessionFixation -> sessionFixation.newSession())
                        .maximumSessions(-1).sessionRegistry(sessionRegistry))
                .addFilterAfter(webSessionActivityFilter,
                        org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator", "/actuator/**").denyAll()
                        .requestMatchers("/wedding/view/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/orders/track", "/orders/track/*").permitAll()
                        // Customer purchases are intentionally forbidden to staff and shippers (403).
                        // Shop operations use /admin/orders and /shipper/**.
                        .requestMatchers("/cart", "/cart/**", "/checkout", "/checkout/**",
                                "/orders", "/orders/**", "/order/success/**", "/products/*/favorite",
                                "/account/favorites", "/account/favorites/**",
                                "/account/recently-viewed", "/account/recently-viewed/**",
                                "/account/reviews", "/account/reviews/**",
                                "/wedding", "/wedding/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers("/products/*/reviews", "/products/*/reviews/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers("/profile/addresses", "/profile/addresses/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers("/seller/onboarding", "/seller/onboarding/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        // Keep private APIs and shop chat closed until their controllers/services
                        // enforce the matching scoped policy. The onboarding flow has its own
                        // customer-only gate above and a feature-flag filter.
                        .requestMatchers("/shops/*/chat", "/shops/*/chat/**",
                                "/seller", "/seller/**")
                        .denyAll()
                        // /vendor is also checked against the active owner membership and
                        // shop state by VendorWorkspaceModelAdvice on every rendered request.
                        .requestMatchers("/vendor", "/vendor/**")
                        .hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/shops", "/shops/**").permitAll()
                        .requestMatchers("/shops/**").denyAll()
                        .requestMatchers("/admin/vendors", "/admin/vendors/**", "/admin/commission", "/admin/commission/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/admin/shops", "/admin/shops/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(
                                "/", "/home", "/products", "/products/**", "/login", "/register", "/forgot-password",
                                "/reset-password", "/verify-email", "/verify-email/resend", "/privacy",
                                "/payments/vnpay/return", "/payments/momo/return",
                                "/preview/**",
                                "/error", "/access-denied",
                                "/.well-known/jwks.json",
                                "/favicon.ico", "/favicon*.ico", "/favicon-*.ico",
                                "/css/**", "/js/**", "/images/**", "/webjars/**", "/wedding/view/**", "/*/k/**",
                                "/w/**",
                                "/api/v1/provinces", "/api/v1/provinces/**",
                                "/api/v1/payments/vnpay/ipn", "/api/v1/payments/vnpay/vnpay_ipn",
                                "/api/v1/payments/vnpay/return", "/api/v1/payments/momo/ipn",
                                "/api/v1/payments/momo/return")
                        .permitAll()
                        // Native WebSocket clients cannot reliably send an
                        // Authorization header during the HTTP handshake;
                        // authentication is enforced on STOMP CONNECT.
                        .requestMatchers("/ws-utewedding/**").permitAll()
                        .requestMatchers("/admin/orders/*/refund", "/admin/returns/*/refund", "/admin/returns/*/retry", "/admin/returns/*/exchange", "/admin/returns/*/exchange/delivered").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/admin/returns", "/admin/returns/**", "/admin/support", "/admin/support/**", "/admin/risk-events", "/admin/risk-events/**", "/admin/finance", "/admin/finance/**").hasAnyRole("MANAGER", "ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/returns", "/returns/**", "/support", "/support/**").hasAnyRole("USER", "CUSTOMER")
                        .requestMatchers("/admin/users", "/admin/users/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/admin/privacy", "/admin/privacy/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/admin", "/admin/dashboard", "/admin/dashboard/**",
                                "/admin/orders", "/admin/orders/**", "/admin/reviews", "/admin/reviews/**",
                                "/admin/review-reports", "/admin/review-reports/**",
                                "/admin/weddings", "/admin/weddings/**",
                                "/admin/payments", "/admin/payments/**",
                                "/admin/fulfillments", "/admin/fulfillments/**", "/admin/products",
                                "/admin/products/**", "/admin/categories", "/admin/categories/**",
                                "/admin/vouchers", "/admin/vouchers/**")
                        .hasAnyRole("ADMIN", "MANAGER", "SUPER_ADMIN")
                        .requestMatchers("/admin/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/profile/privacy", "/profile/privacy/**").authenticated()
                        .requestMatchers("/shipper/**").hasRole("SHIPPER")
                        .requestMatchers("/customer/**").hasAnyRole("CUSTOMER", "USER")
                        .requestMatchers("/user/**")
                         .hasAnyRole("USER", "CUSTOMER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .failureHandler(formLoginAuditHandlers.failureHandler())
                        .successHandler(formLoginAuditHandlers.successHandler())
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    WebSessionActivityFilter webSessionActivityFilter(
            com.utewedding.app.security.WebSessionMetadataService metadata) {
        return new WebSessionActivityFilter(metadata);
    }

    static HttpSessionRequestCache htmlRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(request -> {
            if (!HttpMethod.GET.matches(request.getMethod())) return false;
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            String path = uri != null && contextPath != null && uri.startsWith(contextPath)
                    ? uri.substring(contextPath.length()) : uri;
            if (path == null || path.isBlank() || isStaticRequestPath(path)) return false;
            String accept = request.getHeader("Accept");
            return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/html");
        });
        return cache;
    }

    private static boolean isStaticRequestPath(String path) {
        String normalized = path.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("/favicon") || normalized.startsWith("/css/")
                || normalized.startsWith("/js/") || normalized.startsWith("/images/")
                || normalized.startsWith("/webjars/") || normalized.startsWith("/fonts/")
                || normalized.startsWith("/assets/")
                || normalized.matches(".*\\.(ico|css|js|map|png|jpe?g|gif|svg|webp|woff2?|ttf|eot)$");
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    public static String loginSuccessUrl(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) return "/";
        java.util.Set<String> authorities = authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        if (authentication.getPrincipal() instanceof com.utewedding.app.security.UserPrincipal user
                && user.isVendorAccount()) return "/vendor";
        if (authorities.contains("ROLE_SHIPPER")) return "/shipper/board";
        if (authorities.contains("ROLE_MANAGER") || authorities.contains("ROLE_ADMIN")
                || authorities.contains("ROLE_SUPER_ADMIN")) return "/admin";
        return "/";
    }

    /**
     * CORS is only enabled for browser API calls. Server-rendered HTML forms
     * such as forgot-password are same-origin and must not depend on the SPA
     * origin allowlist. A wildcard origin is rejected when credentials are
     * enabled; production must list the actual API client origins.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") String configuredOrigins) {
        List<String> origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
        if (origins.isEmpty() || origins.stream().anyMatch("*"::equals)) {
            throw new IllegalArgumentException(
                    "app.cors.allowed-origins must contain explicit origins when credentials are enabled");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept", "Authorization", "Content-Type", "Origin", "X-Requested-With",
                "X-CSRF-TOKEN", "X-XSRF-TOKEN", "X-Request-ID", "X-Correlation-ID"));
        configuration.setExposedHeaders(List.of("Location", "X-Request-ID"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * The JWT filter is attached explicitly to each Spring Security chain.
     * Disable Boot's standalone servlet registration so it cannot run a second
     * time outside the security chains.
     */
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private RequestMatcher publicWishRequestMatcher() {
        return request -> "POST".equalsIgnoreCase(request.getMethod())
                && (requestPath(request).matches("/api/v1/weddings/[0-9a-fA-F-]{36}/wishes")
                    || requestPath(request).matches("/api/weddings/[0-9a-fA-F-]{36}/wishes"));
    }

    RequestMatcher bearerOnlyApiRequestMatcher(String refreshCookieName) {
        return request -> {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || authorization.length() <= 7
                    || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies == null) return true;
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if ("JSESSIONID".equalsIgnoreCase(cookie.getName())
                        || refreshCookieName.equals(cookie.getName())) return false;
            }
            return true;
        };
    }

    RequestMatcher paymentPostCallbackRequestMatcher() {
        return request -> "POST".equalsIgnoreCase(request.getMethod())
                && ("/api/v1/payments/vnpay/ipn".equals(requestPath(request))
                    || "/api/v1/payments/vnpay/vnpay_ipn".equals(requestPath(request))
                    || "/api/v1/payments/momo/ipn".equals(requestPath(request)));
    }

    RequestMatcher shippingWebhookRequestMatcher() {
        return request -> "POST".equalsIgnoreCase(request.getMethod())
                && requestPath(request).matches("/api/v1/shipping/providers/[A-Za-z0-9_-]+/webhook");
    }

    private String requestPath(jakarta.servlet.http.HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        return contextPath == null || contextPath.isEmpty() || !uri.startsWith(contextPath)
                ? uri : uri.substring(contextPath.length());
    }

    String cspPolicy() {
        String base = "default-src 'self'; "
                + "base-uri 'self'; object-src 'none'; frame-ancestors 'self'; "
                + "script-src 'self' https://cdn.jsdelivr.net; "
                + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net; "
                + "font-src 'self' https://fonts.gstatic.com data:; "
                + "img-src 'self' data: blob: https:; "
                + "connect-src 'self'; "
                + "form-action 'self' https://sandbox.vnpayment.vn; frame-src 'self'";
        return "production".equalsIgnoreCase(environment) ? base + "; upgrade-insecure-requests" : base;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // New hashes use Argon2id; legacy BCrypt hashes remain readable while
        // the data migration is in progress.
        return new MigratingPasswordEncoder(new Argon2PasswordEncoder(16, 32, 1, 19_456, 2));
    }

    @Bean
    AuthenticationProvider authenticationProvider(
            CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setUserDetailsPasswordService(userDetailsService);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationProvider authenticationProvider) {
        return new ProviderManager(authenticationProvider);
    }

    @Bean
    RoleHierarchy roleHierarchy() {
        RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
        // Preserve the legacy SUPER_ADMIN -> ADMIN implication without making
        // business-specific shipper permissions implicit.
        hierarchy.setHierarchy("""
                ROLE_SUPER_ADMIN > ROLE_ADMIN
                ROLE_CUSTOMER > ROLE_USER
                """);
        return hierarchy;
    }

    /** Applies the staff role hierarchy to method-security expressions. */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}
