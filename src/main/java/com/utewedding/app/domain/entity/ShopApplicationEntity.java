package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ShopApplicationStatus;
import com.utewedding.app.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_applications")
public class ShopApplicationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "applicant_user_id", nullable = false)
    private UUID applicantUserId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopApplicationStatus status = ShopApplicationStatus.SUBMITTED;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "profile_snapshot_enc", nullable = false, columnDefinition = "text")
    private String profileSnapshot;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "reviewer_user_id")
    private UUID reviewerUserId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_reason", length = 1000)
    private String reviewReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopApplicationEntity() { }

    public ShopApplicationEntity(UUID shopId, UUID applicantUserId, int attemptNumber,
                                 String profileSnapshot) {
        this.shopId = shopId;
        this.applicantUserId = applicantUserId;
        this.attemptNumber = attemptNumber;
        this.profileSnapshot = profileSnapshot;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (submittedAt == null) submittedAt = now;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getApplicantUserId() { return applicantUserId; }
    public void setApplicantUserId(UUID value) { applicantUserId = value; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int value) { attemptNumber = value; }
    public ShopApplicationStatus getStatus() { return status; }
    public void setStatus(ShopApplicationStatus value) { status = value; }
    /** Returns the decrypted snapshot; the JPA converter encrypts it in the database. */
    public String getProfileSnapshot() { return profileSnapshot; }
    public void setProfileSnapshot(String value) { profileSnapshot = value; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getReviewerUserId() { return reviewerUserId; }
    public void setReviewerUserId(UUID value) { reviewerUserId = value; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant value) { reviewedAt = value; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String value) { reviewReason = value; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ShopApplicationEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
