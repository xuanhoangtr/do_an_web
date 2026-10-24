package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.entity.OutboxEventEntity;
import com.utewedding.app.service.EmailService;
import com.utewedding.app.service.port.OutboxDispatcher;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationOutboxDispatcher implements OutboxDispatcher {
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    public EmailVerificationOutboxDispatcher(EmailService emailService, ObjectMapper objectMapper) {
        this.emailService = emailService; this.objectMapper = objectMapper;
    }

    @Override public boolean supports(String eventType) { return "EMAIL_VERIFICATION".equals(eventType); }

    @Override
    public void dispatch(OutboxEventEntity event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            emailService.sendVerificationEmail(payload.path("recipient").asText(), payload.path("otp").asText());
        } catch (Exception exception) {
            throw new IllegalStateException("Email verification dispatch failed", exception);
        }
    }
}
