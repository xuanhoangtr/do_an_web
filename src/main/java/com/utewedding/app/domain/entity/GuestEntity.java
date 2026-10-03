package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Convert;
import com.utewedding.app.security.EncryptedStringConverter;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SQLRestriction;

/** Canonical guest aggregate; wedding ownership is represented by weddingId. */
@Entity
@Table(name = "guests")
@SQLRestriction("deleted_at IS NULL")
public class GuestEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "wedding_id", nullable = false) private UUID weddingId;
    @Column(nullable = false, length = 160) private String name;
    @Column(name = "group_name", length = 120) private String groupName;
    @Column(name = "guest_token_hash", nullable = false, unique = true) private String token;
    @Column(name = "max_attendees", nullable = false) private int maxAttendees = 2;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String phone;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String email;
    @Column(name = "salutation", length = 60) private String salutation;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "invitation_message_override", length = 4096) private String invitationMessageOverride;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "first_opened_at") private Instant firstOpenedAt;
    @Column(name = "last_opened_at") private Instant lastOpenedAt;
    @Column(name = "open_count", nullable = false) private int openCount;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "deleted_at") private Instant deletedAt;

    protected GuestEntity() {}
    public GuestEntity(UUID weddingId, String name, String token) {
        this.weddingId = weddingId; this.name = name; this.token = token;
    }
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String value) { groupName = value; }
    public String getToken() { return token; }
    public void setToken(String value) { token = value; }
    public String getGuestTokenHash() { return token; }
    public void setGuestTokenHash(String value) { token = value; }
    public int getMaxAttendees() { return maxAttendees; }
    public void setMaxAttendees(int value) { maxAttendees = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public String getSalutation() { return salutation; }
    public void setSalutation(String value) { salutation = value; }
    public String getInvitationMessageOverride() { return invitationMessageOverride; }
    public void setInvitationMessageOverride(String value) { invitationMessageOverride = value; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant value) { sentAt = value; }
    public Instant getFirstOpenedAt() { return firstOpenedAt; }
    public void setFirstOpenedAt(Instant value) { firstOpenedAt = value; }
    public Instant getLastOpenedAt() { return lastOpenedAt; }
    public void setLastOpenedAt(Instant value) { lastOpenedAt = value; }
    public int getOpenCount() { return openCount; }
    public void setOpenCount(int value) { openCount = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant value) { deletedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GuestEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
