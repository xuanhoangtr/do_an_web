package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PrivacyLegalBasis;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "privacy_consent_records")
public class PrivacyConsentRecordEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 80) private String purpose;
    @Column(nullable = false) private boolean granted;
    @Enumerated(EnumType.STRING) @Column(name = "legal_basis", nullable = false, length = 30)
    private PrivacyLegalBasis legalBasis = PrivacyLegalBasis.CONSENT;
    @Column(name = "notice_version", nullable = false, length = 40) private String noticeVersion;
    @Column(nullable = false, length = 40) private String source;
    @Column(name = "recorded_at", nullable = false, updatable = false) private Instant recordedAt;
    @PrePersist void onCreate() { if (recordedAt == null) recordedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public boolean isGranted() { return granted; }
    public void setGranted(boolean value) { granted = value; }
    public PrivacyLegalBasis getLegalBasis() { return legalBasis; }
    public void setLegalBasis(PrivacyLegalBasis value) { legalBasis = value; }
    public String getNoticeVersion() { return noticeVersion; }
    public void setNoticeVersion(String value) { noticeVersion = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public Instant getRecordedAt() { return recordedAt; }
}
