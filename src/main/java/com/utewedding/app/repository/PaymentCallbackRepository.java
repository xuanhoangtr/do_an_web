package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PaymentCallbackEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCallbackRepository extends JpaRepository<PaymentCallbackEntity, UUID> {
    Optional<PaymentCallbackEntity> findByProviderAndPayloadSha256(String provider, String payloadSha256);
    java.util.List<PaymentCallbackEntity> findByCheckoutGroupIdOrderByReceivedAtDesc(UUID checkoutGroupId);
}
