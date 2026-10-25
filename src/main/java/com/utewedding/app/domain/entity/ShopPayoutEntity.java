package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ShopPayoutStatus;
import com.utewedding.app.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Manual transfer record with a destination snapshot that cannot follow later bank changes. */
@Entity
@Table(name = "shop_payouts", indexes = {
        @Index(name = "idx_shop_payouts_shop_status", columnList = "shop_id,status,created_at"),
        @Index(name = "idx_shop_payouts_queue", columnList = "status,updated_at,id")
})
public class ShopPayoutEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "shop_id", nullable = false, updatable = false) private UUID shopId;
    @Column(name = "settlement_period_id", nullable = false, updatable = false) private UUID settlementPeriodId;
    @Column(name = "destination_id", nullable = false, updatable = false) private UUID destinationId;
    @Column(name = "destination_version", nullable = false, updatable = false) private long destinationVersion;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(nullable = false, length = 3, updatable = false) private String currency = "VND";
    @Column(name = "destination_bank_code_snapshot", nullable = false, length = 40, updatable = false)
    private String destinationBankCodeSnapshot;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "destination_account_number_snapshot", nullable = false, length = 4096, updatable = false)
    private String destinationAccountNumberSnapshot;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "destination_account_holder_snapshot", nullable = false, length = 4096, updatable = false)
    private String destinationAccountHolderSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ShopPayoutStatus status
            = ShopPayoutStatus.REQUESTED;
    @Column(name = "requested_by", nullable = false, updatable = false) private UUID requestedBy;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "approved_by") private UUID approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "processing_by") private UUID processingBy;
    @Column(name = "processing_at") private Instant processingAt;
    @Column(name = "paid_by") private UUID paidBy;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "provider_code", nullable = false, length = 40) private String providerCode = "MANUAL_BANK";
    @Column(name = "provider_reference", length = 190) private String providerReference;
    @Column(name = "proof_media_id") private UUID proofMediaId;
    @Column(length = 1000) private String note;
    @Version @Column(nullable = false) private long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ShopPayoutEntity() { }

    public ShopPayoutEntity(UUID shopId, UUID periodId, ShopPayoutDestinationEntity destination,
                            long amount, UUID requestedBy, Instant requestedAt) {
        this.shopId = shopId;
        this.settlementPeriodId = periodId;
        this.destinationId = destination.getId();
        this.destinationVersion = destination.getVersion();
        this.amount = amount;
        this.destinationBankCodeSnapshot = destination.getBankCode();
        this.destinationAccountNumberSnapshot = destination.getAccountNumber();
        this.destinationAccountHolderSnapshot = destination.getAccountHolderName();
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
    }

    public void approve(UUID actor, Instant at) { status = ShopPayoutStatus.APPROVED; approvedBy = actor; approvedAt = at; }
    public void beginProcessing(UUID actor, Instant at) {
        status = ShopPayoutStatus.PROCESSING; processingBy = actor; processingAt = at;
    }
    public void markPaid(UUID actor, String reference, UUID proofMediaId, String note, Instant at) {
        status = ShopPayoutStatus.PAID; paidBy = actor; paidAt = at;
        providerReference = reference; this.proofMediaId = proofMediaId; this.note = note;
    }
    public void cancel(String note) { status = ShopPayoutStatus.CANCELLED; this.note = note; }

    @PrePersist void onCreate() {
        if (createdAt == null) createdAt = requestedAt == null ? Instant.now() : requestedAt;
        updatedAt = createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public UUID getSettlementPeriodId() { return settlementPeriodId; }
    public UUID getDestinationId() { return destinationId; }
    public long getDestinationVersion() { return destinationVersion; }
    public long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDestinationBankCodeSnapshot() { return destinationBankCodeSnapshot; }
    public String getDestinationAccountNumberSnapshot() { return destinationAccountNumberSnapshot; }
    public String getDestinationAccountHolderSnapshot() { return destinationAccountHolderSnapshot; }
    public ShopPayoutStatus getStatus() { return status; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getProcessingBy() { return processingBy; }
    public Instant getProcessingAt() { return processingAt; }
    public UUID getPaidBy() { return paidBy; }
    public Instant getPaidAt() { return paidAt; }
    public String getProviderCode() { return providerCode; }
    public String getProviderReference() { return providerReference; }
    public UUID getProofMediaId() { return proofMediaId; }
    public String getNote() { return note; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
