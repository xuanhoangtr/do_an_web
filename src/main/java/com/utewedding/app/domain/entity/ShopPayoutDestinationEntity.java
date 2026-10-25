package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ShopPayoutDestinationStatus;
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

/** Versioned bank instructions. Account fields are encrypted at the JPA boundary. */
@Entity
@Table(name = "shop_payout_destinations", indexes = {
        @Index(name = "idx_shop_payout_destinations_shop", columnList = "shop_id,version"),
        @Index(name = "idx_shop_payout_destinations_status", columnList = "status,created_at,id")
})
public class ShopPayoutDestinationEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "shop_id", nullable = false, updatable = false) private UUID shopId;
    @Column(nullable = false, updatable = false) private long version;
    @Column(name = "bank_code", nullable = false, length = 40, updatable = false) private String bankCode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_number", nullable = false, length = 4096, updatable = false)
    private String accountNumber;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_holder_name", nullable = false, length = 4096, updatable = false)
    private String accountHolderName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private ShopPayoutDestinationStatus status
            = ShopPayoutDestinationStatus.PENDING_VERIFICATION;
    @Column(name = "submitted_by", nullable = false, updatable = false) private UUID submittedBy;
    @Column(name = "submitted_at", nullable = false, updatable = false) private Instant submittedAt;
    @Column(name = "verified_by") private UUID verifiedBy;
    @Column(name = "verified_at") private Instant verifiedAt;
    @Column(name = "verification_reference", length = 190) private String verificationReference;
    @Column(name = "verification_note", length = 1000) private String verificationNote;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ShopPayoutDestinationEntity() { }

    public ShopPayoutDestinationEntity(UUID shopId, long version, String bankCode, String accountNumber,
                                       String accountHolderName, UUID submittedBy, Instant submittedAt) {
        this.shopId = shopId;
        this.version = version;
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountHolderName = accountHolderName;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
    }

    public void verify(UUID actorId, String reference, String note, Instant at) {
        status = ShopPayoutDestinationStatus.VERIFIED;
        verifiedBy = actorId;
        verifiedAt = at;
        verificationReference = reference;
        verificationNote = note;
    }

    public void supersede() { status = ShopPayoutDestinationStatus.SUPERSEDED; }
    public void disable() { status = ShopPayoutDestinationStatus.DISABLED; }

    @PrePersist void onCreate() {
        if (createdAt == null) createdAt = submittedAt == null ? Instant.now() : submittedAt;
        updatedAt = createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public long getVersion() { return version; }
    public String getBankCode() { return bankCode; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountHolderName() { return accountHolderName; }
    public ShopPayoutDestinationStatus getStatus() { return status; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public String getVerificationReference() { return verificationReference; }
    public String getVerificationNote() { return verificationNote; }
    public long getRowVersion() { return rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
