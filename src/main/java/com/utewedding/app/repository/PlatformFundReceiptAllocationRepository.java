package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PlatformFundReceiptAllocationEntity;
import com.utewedding.app.domain.PlatformFundReceiptSourceKind;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformFundReceiptAllocationRepository
        extends JpaRepository<PlatformFundReceiptAllocationEntity, UUID> {
    List<PlatformFundReceiptAllocationEntity> findByReceiptIdOrderByCreatedAtAsc(UUID receiptId);
    boolean existsBySourceKindAndSourceReference(PlatformFundReceiptSourceKind sourceKind, String sourceReference);
    boolean existsBySourceKindAndSourceReferenceAndOrderId(
            PlatformFundReceiptSourceKind sourceKind, String sourceReference, UUID orderId);
    boolean existsByFulfillmentIdAndSourceKindIn(UUID fulfillmentId,
                                                  Collection<PlatformFundReceiptSourceKind> sourceKinds);
}
