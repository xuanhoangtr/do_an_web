package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "templates")
public class TemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 80)
    private String key;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "is_premium", nullable = false)
    private boolean premium;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isPremium() { return premium; }
    public void setPremium(boolean premium) { this.premium = premium; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TemplateEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
