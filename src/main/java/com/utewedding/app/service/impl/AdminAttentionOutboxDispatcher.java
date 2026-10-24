package com.utewedding.app.service.impl;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.entity.OutboxEventEntity;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.port.OutboxDispatcher;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminAttentionOutboxDispatcher implements OutboxDispatcher {
    private final AdminAttentionService attention;
    private final UserRepository users;
    private final SimpMessagingTemplate messaging;

    public AdminAttentionOutboxDispatcher(AdminAttentionService attention, UserRepository users,
                                          SimpMessagingTemplate messaging) {
        this.attention = attention;
        this.users = users;
        this.messaging = messaging;
    }

    @Override
    public boolean supports(String eventType) {
        return "ADMIN_ATTENTION_CHANGED".equals(eventType);
    }

    @Override
    public void dispatch(OutboxEventEntity event) {
        var snapshot = attention.snapshot();
        users.findActiveSupportUsers(List.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.SUPER_ADMIN))
                .forEach(user -> messaging.convertAndSendToUser(user.getId().toString(),
                        "/queue/admin/attention", snapshot));
    }
}
