package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopStatus;
import jakarta.persistence.Column;
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
@Table(name = "shops")
public class ShopEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(length = 2000)
    private String description;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "logo_media_id")
    private UUID logoMediaId;

    @Column(name = "banner_media_id")
    private UUID bannerMediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopKind kind = ShopKind.VENDOR;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopStatus status = ShopStatus.PENDING;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopEntity() { }

    public ShopEntity(String code, String slug, String displayName, ShopKind kind, ShopStatus status) {
        this.code = code;
        this.slug = slug;
        this.displayName = displayName;
        this.kind = kind;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String value) { code = value; }
    public String getSlug() { return slug; }
    public void setSlug(String value) { slug = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String value) { contactEmail = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value; }
    public UUID getLogoMediaId() { return logoMediaId; }
    public void setLogoMediaId(UUID value) { logoMediaId = value; }
    public UUID getBannerMediaId() { return bannerMediaId; }
    public void setBannerMediaId(UUID value) { bannerMediaId = value; }
    public ShopKind getKind() { return kind; }
    public void setKind(ShopKind value) { kind = value; }
    public ShopStatus getStatus() { return status; }
    public void setStatus(ShopStatus value) { status = value; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ShopEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
