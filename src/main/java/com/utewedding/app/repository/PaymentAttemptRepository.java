package com.utewedding.app.repository;

import com.utewedding.app.domain.PaymentAttemptStatus;
import com.utewedding.app.domain.entity.PaymentAttemptEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {
    List<PaymentAttemptEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
    List<PaymentAttemptEntity> findByCheckoutGroupIdOrderByCreatedAtDesc(UUID checkoutGroupId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from PaymentAttemptEntity a "
            + "where a.checkoutGroupId = :groupId order by a.createdAt desc, a.id desc")
    List<PaymentAttemptEntity> findByCheckoutGroupIdForUpdateOrderByCreatedAtDesc(
            @org.springframework.data.repository.query.Param("groupId") UUID checkoutGroupId);
    Optional<PaymentAttemptEntity> findByProviderAndProviderTransactionId(String provider, String providerTransactionId);
    Optional<PaymentAttemptEntity> findByProviderAndProviderTxnRef(String provider, String providerTxnRef);
    List<PaymentAttemptEntity> findByStatusInAndUpdatedAtBefore(
            Collection<PaymentAttemptStatus> statuses, Instant updatedAt, Pageable pageable);
}
