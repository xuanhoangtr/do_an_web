package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "support_message_attachments")
public class SupportMessageAttachmentEntity {
    @EmbeddedId private SupportMessageAttachmentId id;
    @Column(name = "shop_id") private UUID shopId;

    protected SupportMessageAttachmentEntity() { }
    public SupportMessageAttachmentEntity(SupportMessageAttachmentId id) { this(id, null); }
    public SupportMessageAttachmentEntity(SupportMessageAttachmentId id, UUID shopId) {
        this.id = id;
        this.shopId = shopId;
    }
    public SupportMessageAttachmentId getId() { return id; }
    public UUID getShopId() { return shopId; }
}
