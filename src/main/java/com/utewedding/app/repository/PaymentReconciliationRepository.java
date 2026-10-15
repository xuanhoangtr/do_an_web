package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PaymentReconciliationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentReconciliationRepository extends JpaRepository<PaymentReconciliationEntity, UUID> {
    java.util.Optional<PaymentReconciliationEntity>
    findFirstByOrderIdAndReasonAndResolutionOrderByCheckedAtDesc(
            UUID orderId, com.utewedding.app.domain.PaymentReconciliationReason reason,
            com.utewedding.app.domain.PaymentReconciliationResolution resolution);
    java.util.Optional<PaymentReconciliationEntity>
    findFirstByOrderIdAndAttemptIdAndReasonAndResolutionOrderByCheckedAtDesc(
            UUID orderId, UUID attemptId, com.utewedding.app.domain.PaymentReconciliationReason reason,
            com.utewedding.app.domain.PaymentReconciliationResolution resolution);
}
