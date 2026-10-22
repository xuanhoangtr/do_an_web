package com.utewedding.app.domain.entity;
import com.utewedding.app.domain.CodReconciliationStatus;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="cod_reconciliations")
public class CodReconciliationEntity {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="order_id",nullable=false,unique=true) private UUID orderId;
 @Column(name="original_due_amount",nullable=false) private long originalDueAmount;
 @Column(name="final_due_amount",nullable=false) private long finalDueAmount;
 @Column(name="collected_amount",nullable=false) private long collectedAmount;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private CodReconciliationStatus status=CodReconciliationStatus.OPEN;
 @Column(name="resolution_reference",length=190) private String resolutionReference;
 @Column(name="resolution_note",length=1000) private String resolutionNote;
 @Column(name="resolved_by") private UUID resolvedBy; @Column(name="resolved_at") private Instant resolvedAt;
 @Version private long version; @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 @PrePersist void create(){createdAt=updatedAt=Instant.now();} @PreUpdate void update(){updatedAt=Instant.now();}
 public UUID getId(){return id;} public UUID getOrderId(){return orderId;} public void setOrderId(UUID v){orderId=v;}
 public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
 public long getOriginalDueAmount(){return originalDueAmount;} public void setOriginalDueAmount(long v){originalDueAmount=v;}
 public long getFinalDueAmount(){return finalDueAmount;} public void setFinalDueAmount(long v){finalDueAmount=v;}
 public long getCollectedAmount(){return collectedAmount;} public void setCollectedAmount(long v){collectedAmount=v;}
 public CodReconciliationStatus getStatus(){return status;} public void setStatus(CodReconciliationStatus v){status=v;}
 public void resolve(UUID actor,String ref,String note){resolvedBy=actor;resolutionReference=ref;resolutionNote=note;resolvedAt=Instant.now();status=CodReconciliationStatus.RESOLVED;}
}
