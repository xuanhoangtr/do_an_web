package com.utewedding.app.config;

import com.utewedding.app.security.CustomUserDetailsService;
import com.utewedding.app.security.JwtTokenProvider;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.security.WebSessionMetadataService;
import com.utewedding.app.service.port.SessionService;
import io.jsonwebtoken.Claims;
import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** STOMP broker configuration used by browser notification clients. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer,
        org.springframework.context.ApplicationListener<SessionDisconnectEvent> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final String[] allowedOrigins;
    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final SessionService sessions;
    private final WebSessionMetadataService webSessions;
    private final ConcurrentMap<String, JwtStompChannelInterceptor.StompPrincipal> socketPrincipals =
            new ConcurrentHashMap<>();

    public WebSocketConfig(
            @Value("${app.websocket.allowed-origins:}")
            String allowedOrigins,
            @Value("${app.cors.allowed-origins:}") String corsAllowedOrigins,
            JwtTokenProvider tokenProvider,
            CustomUserDetailsService userDetailsService,
            SessionService sessions,
            WebSessionMetadataService webSessions) {
        Set<String> websocketOrigins = parseOrigins(allowedOrigins);
        Set<String> corsOrigins = parseOrigins(corsAllowedOrigins);
        if (!websocketOrigins.equals(corsOrigins)) {
            log.warn("WebSocket and CORS origin policies differ. CORS={}, WebSocket={}; "
                    + "set WEBSOCKET_ALLOWED_ORIGINS explicitly only when this is intentional.",
                    corsOrigins, websocketOrigins);
        }
        this.allowedOrigins = websocketOrigins.toArray(String[]::new);
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
        this.sessions = sessions;
        this.webSessions = webSessions;
    }

    static Set<String> parseOrigins(String rawOrigins) {
        if (rawOrigins == null || rawOrigins.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rawOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new JwtStompChannelInterceptor(
                tokenProvider, userDetailsService, sessions, webSessions, socketPrincipals));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ActiveSocketOutboundInterceptor(
                sessions, webSessions, userDetailsService, socketPrincipals));
    }

    /** Drops every outbound frame for a revoked socket until its disconnect event arrives. */
    static final class ActiveSocketOutboundInterceptor implements ChannelInterceptor {
        private final SessionService sessions;
        private final WebSessionMetadataService webSessions;
        private final CustomUserDetailsService userDetailsService;
        private final ConcurrentMap<String, JwtStompChannelInterceptor.StompPrincipal> socketPrincipals;

        ActiveSocketOutboundInterceptor(SessionService sessions, WebSessionMetadataService webSessions,
                                        CustomUserDetailsService userDetailsService,
                                        ConcurrentMap<String, JwtStompChannelInterceptor.StompPrincipal> socketPrincipals) {
            this.sessions = sessions;
            this.webSessions = webSessions;
            this.userDetailsService = userDetailsService;
            this.socketPrincipals = socketPrincipals;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
            if (accessor.getMessageType() == SimpMessageType.MESSAGE) {
                String sessionId = accessor.getSessionId();
                JwtStompChannelInterceptor.StompPrincipal stompPrincipal = sessionId == null
                        ? null : socketPrincipals.get(sessionId);
                if (stompPrincipal != null && (!JwtStompChannelInterceptor.isActive(
                        stompPrincipal, sessions, webSessions)
                        || !userDetailsService.isActiveAccount(stompPrincipal.user().getId()))) {
                    // Keep the mapping until SessionDisconnectEvent. Removing it here would
                    // make the next frame look like an untracked internal broker message.
                    return null;
                }
            }
            return message;
        }
    }

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {
        socketPrincipals.remove(event.getSessionId());
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-utewedding")
                .addInterceptors(new BrowserSessionHandshakeInterceptor(webSessions, userDetailsService))
                .setAllowedOriginPatterns(allowedOrigins);
        registry.addEndpoint("/ws-utewedding")
                .addInterceptors(new BrowserSessionHandshakeInterceptor(webSessions, userDetailsService))
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS();
    }

    /** Binds a form-login handshake to its durable JDBC session for STOMP validation. */
    static final class BrowserSessionHandshakeInterceptor implements HandshakeInterceptor {
        static final String WEB_SESSION_ID_ATTRIBUTE = BrowserSessionHandshakeInterceptor.class.getName() + ".sessionId";

        private final WebSessionMetadataService webSessions;
        private final CustomUserDetailsService userDetailsService;

        BrowserSessionHandshakeInterceptor(WebSessionMetadataService webSessions) {
            this(webSessions, null);
        }

        BrowserSessionHandshakeInterceptor(WebSessionMetadataService webSessions,
                                           CustomUserDetailsService userDetailsService) {
            this.webSessions = webSessions;
            this.userDetailsService = userDetailsService;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, Map<String, Object> attributes) {
            if (!(request instanceof ServletServerHttpRequest servletRequest)
                    || !(request.getPrincipal() instanceof Authentication authentication)
                    || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken
                    || !(authentication.getPrincipal() instanceof UserPrincipal user)
                    || user.getSessionId() != null) {
                return true;
            }
            var session = servletRequest.getServletRequest().getSession(false);
            if (session == null || !webSessions.isActiveWebSession(session.getId(), user.getId())
                    || userDetailsService != null && !userDetailsService.isActiveAccount(user.getId())) return false;
            attributes.put(WEB_SESSION_ID_ATTRIBUTE, session.getId());
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Exception exception) {
            // No post-handshake state to release.
        }
    }

    /** Authenticates STOMP CONNECT before subscriptions or application sends. */
    static final class JwtStompChannelInterceptor implements ChannelInterceptor {
        private final JwtTokenProvider tokenProvider;
        private final CustomUserDetailsService userDetailsService;
        private final SessionService sessions;
        private final WebSessionMetadataService webSessions;
        private final ConcurrentMap<String, StompPrincipal> socketPrincipals;

        JwtStompChannelInterceptor(JwtTokenProvider tokenProvider,
                                   CustomUserDetailsService userDetailsService,
                                   SessionService sessions,
                                   WebSessionMetadataService webSessions) {
            this(tokenProvider, userDetailsService, sessions, webSessions, new ConcurrentHashMap<>());
        }

        JwtStompChannelInterceptor(JwtTokenProvider tokenProvider,
                                   CustomUserDetailsService userDetailsService,
                                   SessionService sessions,
                                   WebSessionMetadataService webSessions,
                                   ConcurrentMap<String, StompPrincipal> socketPrincipals) {
            this.tokenProvider = tokenProvider;
            this.userDetailsService = userDetailsService;
            this.sessions = sessions;
            this.webSessions = webSessions;
            this.socketPrincipals = socketPrincipals;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            // Keep the accessor created by StompSubProtocolHandler. Its CONNECT
            // user-change callback updates the WebSocket session principal and
            // SimpUserRegistry; wrapping the message would detach that callback.
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor == null) {
                StompHeaderAccessor detached = StompHeaderAccessor.wrap(message);
                StompCommand detachedCommand = detached.getCommand();
                if (detachedCommand == null) return message;
                if (detachedCommand == StompCommand.DISCONNECT) {
                    if (detached.getSessionId() != null) socketPrincipals.remove(detached.getSessionId());
                    return message;
                }
                throw new BadCredentialsException("WebSocket message accessor is unavailable");
            }
            StompCommand command = accessor.getCommand();
            if (command == null) {
                return message;
            }
            if (command == StompCommand.CONNECT) {
                Authentication authentication = isAuthenticated(accessor.getUser())
                        ? normalizeSessionAuthentication(accessor.getUser(), accessor.getSessionAttributes())
                        : authentication(accessor);
                requireActive(authentication);
                accessor.setUser(authentication);
                if (accessor.getSessionId() != null
                        && authentication.getPrincipal() instanceof StompPrincipal stompPrincipal) {
                    socketPrincipals.put(accessor.getSessionId(), stompPrincipal);
                }
                return message;
            }
            if (command == StompCommand.DISCONNECT) {
                if (accessor.getSessionId() != null) socketPrincipals.remove(accessor.getSessionId());
                return message;
            }
            requireActive(accessor.getUser());
            if ((command == StompCommand.SUBSCRIBE || command == StompCommand.SEND)
                    && !isAuthenticated(accessor.getUser())) {
                throw new BadCredentialsException("WebSocket authentication is required");
            }
            if (command == StompCommand.SEND) {
                throw new BadCredentialsException("WebSocket send destinations are not supported");
            }
            if (command == StompCommand.SUBSCRIBE && !isAllowedPrivateSubscription(accessor.getDestination())) {
                throw new BadCredentialsException("WebSocket destination is not allowed");
            }
            return message;
        }

        private static boolean isAllowedPrivateSubscription(String destination) {
            // Only unresolved Spring user destinations are accepted. Explicit
            // /user/{other-user}/... and global /topic or /queue paths are not.
            return WebSocketDestinationCatalog.allowsPrivateSubscription(destination);
        }

        private static boolean isAuthenticated(java.security.Principal principal) {
            return principal instanceof Authentication authentication
                    && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken);
        }

        private UsernamePasswordAuthenticationToken authentication(StompHeaderAccessor accessor) {
            String authorization = firstHeader(accessor, "Authorization");
            if (authorization == null) {
                authorization = firstHeader(accessor, "access_token");
            }
            if (authorization == null || authorization.length() <= 7
                    || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new BadCredentialsException("WebSocket JWT Bearer credentials are missing");
            }
            try {
                String accessToken = authorization.substring(7).trim();
                Claims claims = tokenProvider.validateAndGetClaims(accessToken);
                UserPrincipal principal = userDetailsService.loadUserById(claims.getSubject())
                        .withSessionId(claims.get("sid", String.class));
                if (!principal.isEmailVerified()) {
                    throw new BadCredentialsException("WebSocket account is not verified");
                }
                Integer tokenPasswordVersion = claims.get("pwdv", Integer.class);
                if (tokenPasswordVersion == null
                        || tokenPasswordVersion != principal.getPasswordVersion()) {
                    throw new BadCredentialsException("WebSocket JWT password version is stale");
                }
                // User destinations are resolved by Principal#getName(). Keep
                // email as the form/API username, but use the immutable user
                // UUID for the socket-only principal because notifications are
                // addressed by UUID.
                requireActive(principal);
                return UsernamePasswordAuthenticationToken.authenticated(
                        new StompPrincipal(principal, null), null, principal.getAuthorities());
            } catch (RuntimeException exception) {
                if (exception instanceof BadCredentialsException badCredentials) {
                    throw badCredentials;
                }
                throw new BadCredentialsException("Invalid WebSocket JWT", exception);
            }
        }

        private Authentication normalizeSessionAuthentication(Principal principal, Map<String, Object> attributes) {
            if (!(principal instanceof Authentication authentication)) {
                throw new BadCredentialsException("WebSocket authentication is required");
            }
            if (authentication.getPrincipal() instanceof StompPrincipal stompPrincipal) {
                requireActive(stompPrincipal);
                return authentication;
            }
            if (authentication.getPrincipal() instanceof UserPrincipal user) {
                String webSessionId = null;
                if (user.getSessionId() == null) {
                    Object value = attributes == null ? null : attributes.get(
                            BrowserSessionHandshakeInterceptor.WEB_SESSION_ID_ATTRIBUTE);
                    webSessionId = value instanceof String id ? id : null;
                    requireActiveWebSession(user, webSessionId);
                } else {
                    requireActive(user);
                }
                return UsernamePasswordAuthenticationToken.authenticated(
                        new StompPrincipal(user, webSessionId), null, authentication.getAuthorities());
            }
            throw new BadCredentialsException("WebSocket principal is invalid");
        }

        private static String firstHeader(StompHeaderAccessor accessor, String name) {
            String value = accessor.getFirstNativeHeader(name);
            return value == null || value.isBlank() ? null : value.trim();
        }

        private void requireActive(Principal principal) {
            if (principal instanceof StompPrincipal stomp) requireActive(stomp);
            else if (principal instanceof Authentication authentication
                    && authentication.getPrincipal() instanceof StompPrincipal stomp) requireActive(stomp);
            else if (principal instanceof Authentication authentication
                    && authentication.getPrincipal() instanceof UserPrincipal user) requireActive(user);
        }

        private void requireActive(UserPrincipal user) {
            if (!isActive(user, sessions) || !userDetailsService.isActiveAccount(user.getId())) {
                throw new BadCredentialsException("WebSocket access session has been revoked or expired");
            }
        }

        private void requireActiveWebSession(UserPrincipal user, String webSessionId) {
            if (user == null || user.getId() == null
                    || !webSessions.isActiveWebSession(webSessionId, user.getId())
                    || !userDetailsService.isActiveAccount(user.getId())) {
                throw new BadCredentialsException("WebSocket browser session has been revoked or expired");
            }
        }

        private void requireActive(StompPrincipal principal) {
            if (!isActive(principal, sessions, webSessions)
                    || !userDetailsService.isActiveAccount(principal.user().getId())) {
                throw new BadCredentialsException("WebSocket access session has been revoked or expired");
            }
        }

        private static boolean isActive(UserPrincipal user, SessionService sessions) {
            if (user == null || user.getId() == null || user.getSessionId() == null) return false;
            try {
                return sessions.isAccessSessionActive(UUID.fromString(user.getSessionId()), user.getId());
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private static boolean isActive(StompPrincipal principal, SessionService sessions,
                                        WebSessionMetadataService webSessions) {
            if (principal == null || principal.user() == null) return false;
            if (principal.user().getSessionId() != null) return isActive(principal.user(), sessions);
            return webSessions != null && principal.user().getId() != null
                    && webSessions.isActiveWebSession(principal.webSessionId(), principal.user().getId());
        }

        static final class StompPrincipal implements Principal {
            private final UserPrincipal user;
            private final String webSessionId;

            private StompPrincipal(UserPrincipal principal, String webSessionId) {
                this.user = principal;
                this.webSessionId = webSessionId;
            }

            UserPrincipal user() { return user; }
            String webSessionId() { return webSessionId; }

            @Override
            public String getName() {
                return user.getId().toString();
            }
        }
    }
}
