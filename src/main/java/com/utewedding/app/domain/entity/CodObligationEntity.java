package com.utewedding.app.domain.entity;
import com.utewedding.app.domain.CodObligationStatus;
import com.utewedding.app.domain.CodRemittanceStatus;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="cod_obligations")
public class CodObligationEntity {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="reconciliation_id",nullable=false) private UUID reconciliationId;
 @Column(name="fulfillment_id",nullable=false,unique=true) private UUID fulfillmentId;
 @Column(name="merchandise_due",nullable=false) private long merchandiseDue;
 @Column(name="allocated_shipping_fee",nullable=false) private long allocatedShippingFee;
 @Column(name="expected_collectable",nullable=false) private long expectedCollectable;
 @Column(name="actual_collected") private Long actualCollected;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private CodObligationStatus status=CodObligationStatus.PENDING;
 @Column(name="collection_reference",length=190) private String collectionReference; @Column(name="collected_at") private Instant collectedAt;
 @Column(name="system_receipt_code",length=32) private String systemReceiptCode;
 @Enumerated(EnumType.STRING) @Column(name="remittance_status",nullable=false,length=20) private CodRemittanceStatus remittanceStatus=CodRemittanceStatus.NOT_DUE;
 @Column(name="remitted_amount",nullable=false) private long remittedAmount;
 @Column(name="remittance_reference",length=190) private String remittanceReference;
 @Column(name="remittance_note",length=1000) private String remittanceNote;
 @Column(name="remitted_by") private UUID remittedBy;
 @Column(name="remitted_at") private Instant remittedAt;
 @Version private long version; @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
 @PrePersist void create(){createdAt=updatedAt=Instant.now();} @PreUpdate void update(){updatedAt=Instant.now();}
 public UUID getId(){return id;} public UUID getReconciliationId(){return reconciliationId;} public void setReconciliationId(UUID v){reconciliationId=v;}
 public UUID getFulfillmentId(){return fulfillmentId;} public void setFulfillmentId(UUID v){fulfillmentId=v;}
 public long getMerchandiseDue(){return merchandiseDue;} public void setMerchandiseDue(long v){merchandiseDue=v;}
 public long getAllocatedShippingFee(){return allocatedShippingFee;} public void setAllocatedShippingFee(long v){allocatedShippingFee=v;}
 public long getExpectedCollectable(){return expectedCollectable;} public void setExpectedCollectable(long v){expectedCollectable=v;}
 public Long getActualCollected(){return actualCollected;} public void setActualCollected(Long v){actualCollected=v;}
 public CodObligationStatus getStatus(){return status;} public void setStatus(CodObligationStatus v){status=v;}
 public String getCollectionReference(){return collectionReference;} public void setCollectionReference(String v){collectionReference=v;}
 public String getSystemReceiptCode(){return systemReceiptCode;} public void setSystemReceiptCode(String v){systemReceiptCode=v;}
 public CodRemittanceStatus getRemittanceStatus(){return remittanceStatus;} public void setRemittanceStatus(CodRemittanceStatus v){remittanceStatus=v;}
 public long getRemittedAmount(){return remittedAmount;} public void setRemittedAmount(long v){remittedAmount=v;}
 public String getRemittanceReference(){return remittanceReference;} public void setRemittanceReference(String v){remittanceReference=v;}
 public String getRemittanceNote(){return remittanceNote;} public void setRemittanceNote(String v){remittanceNote=v;}
 public UUID getRemittedBy(){return remittedBy;} public void setRemittedBy(UUID v){remittedBy=v;}
 public Instant getRemittedAt(){return remittedAt;} public void setRemittedAt(Instant v){remittedAt=v;}
 public Instant getCollectedAt(){return collectedAt;} public void setCollectedAt(Instant v){collectedAt=v;}
}
