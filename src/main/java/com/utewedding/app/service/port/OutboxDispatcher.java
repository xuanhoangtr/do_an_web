package com.utewedding.app.service.port;

import com.utewedding.app.domain.entity.OutboxEventEntity;

public interface OutboxDispatcher {
    boolean supports(String eventType);
    void dispatch(OutboxEventEntity event);
}