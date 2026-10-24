package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.RiskAction;
import com.utewedding.app.domain.RiskRuleMode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "risk_rule_configs")
public class RiskRuleConfigEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "rule_code", nullable = false, length = 60) private String ruleCode;
    @Column(nullable = false) private int version;
    @Column(nullable = false) private boolean enabled = true;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RiskRuleMode mode = RiskRuleMode.SHADOW;
    @Column(nullable = false, length = 20) private String severity;
    @Enumerated(EnumType.STRING) @Column(name = "recommended_action", nullable = false, length = 30)
    private RiskAction recommendedAction;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private String parameters = "{}";
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @PrePersist void create() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getRuleCode() { return ruleCode; }
    public int getVersion() { return version; }
    public boolean isEnabled() { return enabled; }
    public RiskRuleMode getMode() { return mode; }
    public String getSeverity() { return severity; }
    public RiskAction getRecommendedAction() { return recommendedAction; }
    public String getParameters() { return parameters; }
    public void setRuleCode(String value) { ruleCode = value; }
    public void setVersion(int value) { version = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setMode(RiskRuleMode value) { mode = value; }
    public void setSeverity(String value) { severity = value; }
    public void setRecommendedAction(RiskAction value) { recommendedAction = value; }
    public void setParameters(String value) { parameters = value; }
}
