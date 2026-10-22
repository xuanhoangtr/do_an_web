package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "return_resolution_items")
@IdClass(ReturnResolutionItemEntity.Key.class)
public class ReturnResolutionItemEntity {
    @Id @Column(name = "resolution_id") private UUID resolutionId;
    @Id @Column(name = "return_case_item_id") private UUID returnCaseItemId;
    @Column(nullable = false) private int quantity;
    @Column(nullable = false) private long amount;
    protected ReturnResolutionItemEntity() {}
    public ReturnResolutionItemEntity(UUID resolutionId, UUID caseItemId, int quantity, long amount) {
        this.resolutionId = resolutionId; this.returnCaseItemId = caseItemId; this.quantity = quantity; this.amount = amount;
    }
    public UUID getReturnCaseItemId(){return returnCaseItemId;}
    public int getQuantity(){return quantity;}
    public long getAmount(){return amount;}
    public record Key(UUID resolutionId, UUID returnCaseItemId) implements Serializable {}
}