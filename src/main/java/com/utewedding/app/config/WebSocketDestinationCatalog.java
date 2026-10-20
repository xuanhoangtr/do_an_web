package com.utewedding.app.config;

import java.util.List;

/** Canonical private STOMP subscription destinations shared by server and rendered clients. */
public final class WebSocketDestinationCatalog {

    public static final List<String> PRIVATE_SUBSCRIPTIONS = List.of(
            "/user/queue/notifications",
            "/user/queue/orders",
            "/user/queue/wishes",
            "/user/queue/fulfillment",
            "/user/queue/support",
            "/user/queue/support-chat",
            "/user/queue/admin/attention");

    private WebSocketDestinationCatalog() {
    }

    public static boolean allowsPrivateSubscription(String destination) {
        return destination != null && PRIVATE_SUBSCRIPTIONS.contains(destination);
    }
}
