package com.utewedding.app.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Publishes the canonical WebSocket destinations to server-rendered pages. */
@ControllerAdvice(annotations = Controller.class)
public class WebSocketDestinationModelAdvice {

    @ModelAttribute("webSocketDestinations")
    public java.util.List<String> webSocketDestinations() {
        return WebSocketDestinationCatalog.PRIVATE_SUBSCRIPTIONS;
    }
}
