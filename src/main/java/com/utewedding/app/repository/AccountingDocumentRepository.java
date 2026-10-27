package com.utewedding.app.repository;

import com.utewedding.app.domain.AccountingDocumentType;
import com.utewedding.app.domain.entity.AccountingDocumentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountingDocumentRepository extends JpaRepository<AccountingDocumentEntity, UUID> {
    List<AccountingDocumentEntity> findByOrderIdOrderByIssuedAtDesc(UUID orderId);
    Optional<AccountingDocumentEntity> findFirstByOrderIdAndDocumentTypeOrderByIssuedAtDesc(
            UUID orderId, AccountingDocumentType type);
    Optional<AccountingDocumentEntity> findFirstByOrderIdAndDocumentTypeAndPaymentReferenceOrderByIssuedAtDesc(
            UUID orderId, AccountingDocumentType type, String paymentReference);
}
