package com.utewedding.app.domain.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="inventory_adjustments")
public class ReturnInventoryAdjustmentEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="return_case_id") private UUID returnCaseId;
    @Column(name="return_resolution_id") private UUID returnResolutionId;
    @Column(name="product_id",nullable=false) private UUID productId;
    @Column(nullable=false) private int quantity;
    @Column(nullable=false,length=30) private String kind;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ReturnInventoryAdjustmentEntity(){}
    public ReturnInventoryAdjustmentEntity(UUID caseId,UUID resolutionId,UUID productId,int quantity,String kind){this.returnCaseId=caseId;this.returnResolutionId=resolutionId;this.productId=productId;this.quantity=quantity;this.kind=kind;createdAt=Instant.now();}
}
