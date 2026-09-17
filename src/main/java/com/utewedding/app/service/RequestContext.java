package com.utewedding.app.service;

/** Request metadata used by audit/session ports; it is never exposed as a domain entity. */
public record RequestContext(String ipAddress, String userAgent, String requestId, String actorRole) {
    public RequestContext(String ipAddress, String userAgent, String requestId) {
        this(ipAddress, userAgent, requestId, null);
    }
}
