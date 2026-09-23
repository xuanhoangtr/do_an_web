package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_notifications", indexes = {
        @Index(name = "idx_user_notifications_recipient_created", columnList = "recipient_id, created_at"),
        @Index(name = "idx_user_notifications_recipient_read", columnList = "recipient_id, read_at")
})
public class UserNotificationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;
    @Column(nullable = false, length = 40)
    private String type;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(nullable = false, length = 1000)
    private String body;
    @Column(name = "target_url", length = 500)
    private String targetUrl;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRecipientId() { return recipientId; }
    public void setRecipientId(UUID value) { recipientId = value; }
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getBody() { return body; }
    public void setBody(String value) { body = value; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String value) { targetUrl = value; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant value) { readAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UserNotificationEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
