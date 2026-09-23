package com.utewedding.app.service.web;

import com.utewedding.app.exception.BusinessConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Server-created, session-bound tokens survive double submits and are scoped to a single form action. */
@Component
public class FormToken {
    private static final String SESSION_ATTRIBUTE = FormToken.class.getName();

    private record Token(String scope, Instant expires, String confirmation, boolean completed)
            implements java.io.Serializable {
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Token> tokens(HttpSession session) {
        Object stored = session.getAttribute(SESSION_ATTRIBUTE);
        LinkedHashMap<String, Token> tokens = stored == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>((Map<String, Token>) stored);
        boolean expired = tokens.values().removeIf(token -> token.expires().isBefore(Instant.now()));
        if (stored == null || expired) {
            session.setAttribute(SESSION_ATTRIBUTE, tokens);
        }
        return tokens;
    }

    private void save(HttpSession session, LinkedHashMap<String, Token> tokens) {
        // JDBC sessions use ON_SET_ATTRIBUTE, so each mutation must replace the
        // attribute instead of relying on dirty tracking of a nested Map.
        session.setAttribute(SESSION_ATTRIBUTE, tokens);
    }

    public String issue(HttpServletRequest request, String scope) {
        HttpSession session = request.getSession();
        synchronized (session) {
            LinkedHashMap<String, Token> tokens = tokens(session);
            for (var entry : tokens.entrySet()) {
                if (entry.getValue().scope().equals(scope) && !entry.getValue().completed()) {
                    return entry.getKey();
                }
            }
            while (tokens.size() >= 128) {
                tokens.remove(tokens.keySet().iterator().next());
            }
            String key = UUID.randomUUID().toString();
            tokens.put(key, new Token(scope, Instant.now().plusSeconds(86400), null, false));
            save(session, tokens);
            return key;
        }
    }

    public void complete(HttpServletRequest request, String key) {
        HttpSession session = request.getSession();
        synchronized (session) {
            LinkedHashMap<String, Token> tokens = tokens(session);
            Token token = tokens.get(key);
            if (token != null) {
                tokens.put(key, new Token(token.scope(), token.expires(), token.confirmation(), true));
                save(session, tokens);
            }
        }
    }

    public String verify(HttpServletRequest request, String scope, String key) {
        HttpSession session = request.getSession();
        synchronized (session) {
            requireToken(tokens(session), scope, key);
            return key;
        }
    }

    public boolean confirmed(HttpServletRequest request, String scope, String key,
                             Map<String, String> values, boolean confirmed) {
        HttpSession session = request.getSession();
        synchronized (session) {
            LinkedHashMap<String, Token> tokens = tokens(session);
            Token token = requireToken(tokens, scope, key);
            String digest = digest(values);
            if (confirmed) {
                if (!digest.equals(token.confirmation())) {
                    throw new BusinessConflictException("Nội dung xác nhận đã thay đổi. Hãy xác nhận lại.");
                }
                return true;
            }
            tokens.put(key, new Token(scope, token.expires(), digest, token.completed()));
            save(session, tokens);
            return false;
        }
    }

    private static Token requireToken(Map<String, Token> tokens, String scope, String key) {
        Token token = tokens.get(key);
        if (token == null || !token.scope().equals(scope)) {
            throw new BusinessConflictException("Biểu mẫu đã hết hạn. Hãy tải lại trang.");
        }
        return token;
    }

    private static String digest(Map<String, String> values) {
        var canonical = new StringBuilder();
        new TreeMap<>(values).forEach((name, value) -> canonical.append(name.length())
                .append(':').append(name).append(value.length()).append(':').append(value));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
