package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.RsvpStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Convert;
import com.utewedding.app.security.EncryptedStringConverter;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** Canonical RSVP aggregate with ids instead of object graph relationships. */
@Entity
@Table(name = "rsvps")
public class RsvpEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "wedding_id", nullable = false) private UUID weddingId;
    @Column(name = "guest_id", nullable = false, unique = true) private UUID guestId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "attendance_status", nullable = false, columnDefinition = "\"RsvpStatus\"")
    private RsvpStatus attendanceStatus;
    @Column(name = "attendee_count", nullable = false) private int attendeeCount = 1;
    @Column(name = "invitee_count", nullable = false) private int inviteeCount = 1;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "guest_name", length = 4096) private String guestName;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String phone;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4096) private String note;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "dietary_notes", length = 4096) private String dietaryNotes;
    @CreationTimestamp @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public RsvpEntity() {}
    public UUID getId() { return id; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public UUID getGuestId() { return guestId; }
    public void setGuestId(UUID value) { guestId = value; }
    public RsvpStatus getAttendanceStatus() { return attendanceStatus; }
    public void setAttendanceStatus(RsvpStatus value) { attendanceStatus = value; }
    public int getAttendeeCount() { return attendeeCount; }
    public void setAttendeeCount(int value) { attendeeCount = value; }
    public int getInviteeCount() { return inviteeCount; }
    public void setInviteeCount(int value) { inviteeCount = value; }
    public String getGuestName() { return guestName; }
    public void setGuestName(String value) { guestName = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public String getDietaryNotes() { return dietaryNotes; }
    public void setDietaryNotes(String value) { dietaryNotes = value; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RsvpEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
