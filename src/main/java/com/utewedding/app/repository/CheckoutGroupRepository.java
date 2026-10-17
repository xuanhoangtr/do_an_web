package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CheckoutGroupEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CheckoutGroupRepository extends JpaRepository<CheckoutGroupEntity, UUID> {

    Optional<CheckoutGroupEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CheckoutGroupEntity g where g.id = :id")
    Optional<CheckoutGroupEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CheckoutGroupEntity g where g.paymentStatus in "
            + "(com.utewedding.app.domain.CheckoutGroupPaymentStatus.PENDING, "
            + "com.utewedding.app.domain.CheckoutGroupPaymentStatus.PROCESSING) "
            + "and g.expiresAt is not null and g.expiresAt <= :now order by g.id")
    java.util.List<CheckoutGroupEntity> findPendingExpiredForUpdate(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CheckoutGroupEntity g where g.customerId = :customerId and g.idempotencyKey = :key")
    Optional<CheckoutGroupEntity> findByCustomerIdAndIdempotencyKeyForUpdate(
            @Param("customerId") UUID customerId, @Param("key") String idempotencyKey);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = """
            INSERT INTO checkout_groups (
                id, customer_id, group_number, payment_method, currency,
                subtotal, product_discount_amount, voucher_discount_amount,
                shipping_fee_before_discount, shipping_discount_amount, shipping_fee, total_amount,
                payment_status, lifecycle_status, fulfillment_status, expires_at,
                policy_version, selection_revision, idempotency_key, request_hash, request_hash_version,
                version, created_at, updated_at
            ) VALUES (
                gen_random_uuid(), :customerId, :groupNumber, :paymentMethod, :currency,
                0, 0, 0, 0, 0, 0, 0,
                'PENDING', 'PENDING', 'PENDING', :expiresAt,
                :policyVersion, :selectionRevision, :key, :requestHash, 1,
                0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (customer_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claimIfAbsent(@Param("customerId") UUID customerId,
                      @Param("key") String key,
                      @Param("requestHash") String requestHash,
                      @Param("groupNumber") String groupNumber,
                      @Param("paymentMethod") String paymentMethod,
                      @Param("currency") String currency,
                      @Param("expiresAt") Instant expiresAt,
                      @Param("policyVersion") int policyVersion,
                      @Param("selectionRevision") String selectionRevision);
}
