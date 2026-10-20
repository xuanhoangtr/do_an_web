package com.utewedding.app.exception;

public class SupportChatParticipationRequiredException extends RuntimeException {
    private final String assignedAgentName;
    public SupportChatParticipationRequiredException(String assignedAgentName) {
        super("Nhân viên khác đang hỗ trợ hội thoại này.");
        this.assignedAgentName = assignedAgentName;
    }
    public String getAssignedAgentName() { return assignedAgentName; }
}
