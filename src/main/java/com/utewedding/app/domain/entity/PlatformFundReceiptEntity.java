package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PlatformFundReceiptEnvironment;
import com.utewedding.app.domain.PlatformFundReceiptSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "platform_fund_receipts", indexes = {
        @Index(name = "idx_platform_fund_receipts_received_at", columnList = "received_at,id"),
        @Index(name = "idx_platform_fund_receipts_environment", columnList = "environment,source_code,received_at")
}, uniqueConstraints = @UniqueConstraint(name = "uq_platform_fund_receipt_source", columnNames = {
        "source_code", "provider_code", "provider_account_reference", "environment", "source_identity"
}))
public class PlatformFundReceiptEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_code", nullable = false, length = 32)
    private PlatformFundReceiptSource sourceCode;

    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;

    @Column(name = "provider_account_reference", nullable = false, length = 120)
    private String providerAccountReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformFundReceiptEnvironment environment;

    @Column(name = "source_identity", nullable = false, length = 190)
    private String sourceIdentity;

    @Column(nullable = false, length = 3)
    private String currency = "VND";

    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "deduction_amount", nullable = false)
    private long deductionAmount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Column(name = "external_reference", length = 190)
    private String externalReference;

    @Column(name = "evidence_reference", length = 512)
    private String evidenceReference;

    @Column(name = "evidence_note", length = 1000)
    private String evidenceNote;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformFundReceiptEntity() { }

    public PlatformFundReceiptEntity(PlatformFundReceiptSource sourceCode, String providerCode,
                                     String providerAccountReference,
                                     PlatformFundReceiptEnvironment environment, String sourceIdentity,
                                     long grossAmount, long feeAmount, long deductionAmount, long netAmount,
                                     String externalReference, String evidenceReference, String evidenceNote,
                                     Instant receivedAt, UUID recordedBy) {
        this.sourceCode = sourceCode;
        this.providerCode = providerCode;
        this.providerAccountReference = providerAccountReference;
        this.environment = environment;
        this.sourceIdentity = sourceIdentity;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.deductionAmount = deductionAmount;
        this.netAmount = netAmount;
        this.externalReference = externalReference;
        this.evidenceReference = evidenceReference;
        this.evidenceNote = evidenceNote;
        this.receivedAt = receivedAt;
        this.recordedBy = recordedBy;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public PlatformFundReceiptSource getSourceCode() { return sourceCode; }
    public String getProviderCode() { return providerCode; }
    public String getProviderAccountReference() { return providerAccountReference; }
    public PlatformFundReceiptEnvironment getEnvironment() { return environment; }
    public String getSourceIdentity() { return sourceIdentity; }
    public String getCurrency() { return currency; }
    public long getGrossAmount() { return grossAmount; }
    public long getFeeAmount() { return feeAmount; }
    public long getDeductionAmount() { return deductionAmount; }
    public long getNetAmount() { return netAmount; }
    public String getExternalReference() { return externalReference; }
    public String getEvidenceReference() { return evidenceReference; }
    public String getEvidenceNote() { return evidenceNote; }
    public Instant getReceivedAt() { return receivedAt; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
