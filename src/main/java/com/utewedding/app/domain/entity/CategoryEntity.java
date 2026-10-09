package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Canonical catalog category aggregate. Relationships are represented by ids. */
@Entity
@Table(name = "categories")
public class CategoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "parent_id") private Long parentId;
    @Column(nullable = false, unique = true, length = 120) private String name;
    @Column(nullable = false, unique = true, length = 120) private String slug;
    @Column(length = 1000) private String description;
    @Column(name = "image_url", length = 2048) private String imageUrl;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CategoryEntity() {}

    public CategoryEntity(String name, String slug) { this.name = name; this.slug = slug; }
    public Long getId() { return id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long value) { parentId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getSlug() { return slug; }
    public void setSlug(String value) { slug = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String value) { imageUrl = value; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int value) { displayOrder = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CategoryEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
