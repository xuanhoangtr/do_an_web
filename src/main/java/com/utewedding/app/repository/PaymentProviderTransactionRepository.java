package com.utewedding.app.repository;

import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.entity.PaymentProviderTransactionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PaymentProviderTransactionRepository extends JpaRepository<PaymentProviderTransactionEntity, UUID> {
    Optional<PaymentProviderTransactionEntity> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentProviderTransactionEntity> findByProviderAndProviderTransactionIdAndTransactionType(
            String provider, String providerTransactionId, ProviderTransactionType transactionType);
    List<PaymentProviderTransactionEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
    List<PaymentProviderTransactionEntity> findByReturnResolutionIdOrderByCreatedAtAsc(UUID returnResolutionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentProviderTransactionEntity t where t.idempotencyKey = :key")
    Optional<PaymentProviderTransactionEntity> findByIdempotencyKeyForUpdate(@Param("key") String key);
    @Query(value = "select id from payment_provider_transactions where transaction_type = 'REFUND' "
            + "and (status = 'PENDING' or (status = 'PROCESSING' and processed_at <= :staleBefore)) "
            + "order by created_at, id limit :limit", nativeQuery = true)
    List<UUID> findRefundIntentsDue(@Param("staleBefore") Instant staleBefore, @Param("limit") int limit);
}
