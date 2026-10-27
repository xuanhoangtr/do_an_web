package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity
@Table(name = "accounting_document_sellers")
public class AccountingDocumentSellerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seller_snapshot", columnDefinition = "jsonb", nullable = false)
    private String sellerSnapshot;

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID value) { documentId = value; }
    public String getSellerSnapshot() { return sellerSnapshot; }
    public void setSellerSnapshot(String value) { sellerSnapshot = value; }
}
