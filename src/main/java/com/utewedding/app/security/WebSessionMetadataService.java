package com.utewedding.app.security;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Stores non-secret login metadata alongside JDBC-backed browser sessions. */
@Service
public class WebSessionMetadataService {
    private final JdbcTemplate jdbc;

    public WebSessionMetadataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void recordLogin(String sessionId, UUID userId, String ipAddress, String userAgent) {
        if (sessionId == null || sessionId.isBlank() || userId == null) return;
        Instant now = Instant.now();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update("""
                insert into web_session_metadata(session_id, user_id, login_at, last_activity_at, ip_address, user_agent)
                values (?, ?, ?, ?, ?, ?)
                on conflict (session_id) do update set user_id = excluded.user_id,
                    login_at = excluded.login_at, last_activity_at = excluded.last_activity_at,
                    ip_address = excluded.ip_address, user_agent = excluded.user_agent
                """, sessionId, userId, timestamp, timestamp, truncate(ipAddress, 64), truncate(userAgent, 512));
    }

    @Transactional
    public void recordActivity(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        jdbc.update("update web_session_metadata set last_activity_at = ? where session_id = ?",
                OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC), sessionId);
    }

    /** Confirms a browser session still exists, has not expired, and belongs to this account. */
    @Transactional(readOnly = true)
    public boolean isActiveWebSession(String sessionId, UUID userId) {
        if (sessionId == null || sessionId.isBlank() || userId == null) return false;
        Long active = jdbc.queryForObject("""
                select count(*)
                from spring_session s
                join web_session_metadata m on m.session_id = s.session_id
                where s.session_id = ? and s.expiry_time > ? and m.user_id = ?
                """, Long.class, sessionId, Instant.now().toEpochMilli(), userId);
        return active != null && active > 0;
    }

    @Transactional
    public boolean revoke(String sessionId, String email) {
        if (sessionId == null || sessionId.isBlank() || email == null || email.isBlank()) return false;
        int deleted = jdbc.update("delete from spring_session where session_id = ? and lower(principal_name) = lower(?)",
                sessionId, email);
        if (deleted > 0) jdbc.update("delete from web_session_metadata where session_id = ?", sessionId);
        return deleted > 0;
    }

    /** Removes persisted sessions by stable account id and legacy principal names. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> revokeUserSessions(UUID userId, String principalName, String retainedSessionId) {
        if (userId == null) return List.of();
        List<String> principals = principalName == null || principalName.isBlank()
                ? List.of() : List.of(principalName.trim().toLowerCase(java.util.Locale.ROOT));
        String principalPredicate = principals.isEmpty() ? ""
                : " or lower(s.principal_name) in (" + String.join(",", java.util.Collections.nCopies(principals.size(), "?")) + ")";
        String retainedPredicate = retainedSessionId == null ? "" : " and s.session_id <> ?";
        List<Object> args = new ArrayList<>();
        args.add(Instant.now().toEpochMilli());
        args.add(userId);
        args.addAll(principals);
        if (retainedSessionId != null) args.add(retainedSessionId);
        List<String> sessionIds = jdbc.query("""
                select s.session_id
                from spring_session s
                left join web_session_metadata m on m.session_id = s.session_id
                where s.expiry_time > ? and (m.user_id = ?%s)%s
                """.formatted(principalPredicate, retainedPredicate),
                (rs, row) -> rs.getString(1).trim(), args.toArray());
        if (sessionIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(sessionIds.size(), "?"));
        jdbc.update("delete from spring_session where session_id in (" + placeholders + ")", sessionIds.toArray());
        jdbc.update("delete from web_session_metadata where session_id in (" + placeholders + ")", sessionIds.toArray());
        return sessionIds;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        jdbc.update("delete from spring_session where session_id = ?", sessionId);
        jdbc.update("delete from web_session_metadata where session_id = ?", sessionId);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
