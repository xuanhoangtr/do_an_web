package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PrivacyLegalBasis;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "privacy_retention_rules")
public class PrivacyRetentionRuleEntity {
    @Id private UUID id;
    @Column(name = "category_key", nullable = false, unique = true, length = 80) private String categoryKey;
    @Column(name = "retention_days") private Integer retentionDays;
    @Column(name = "trigger_event", nullable = false, length = 80) private String triggerEvent;
    @Column(nullable = false, length = 30) private String disposition;
    @Enumerated(EnumType.STRING) @Column(name = "legal_basis", nullable = false, length = 30)
    private PrivacyLegalBasis legalBasis;
    @Column(nullable = false, length = 1000) private String description;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    public UUID getId() { return id; }
    public String getCategoryKey() { return categoryKey; }
    public Integer getRetentionDays() { return retentionDays; }
    public String getTriggerEvent() { return triggerEvent; }
    public String getDisposition() { return disposition; }
    public PrivacyLegalBasis getLegalBasis() { return legalBasis; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
