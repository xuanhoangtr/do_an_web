package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.RiskCorrelationEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskCorrelationRepository extends JpaRepository<RiskCorrelationEntity, UUID> {
    boolean existsByOrderId(UUID orderId);

    @Query(value = "select count(distinct account_id) from risk_correlations "
            + "where voucher_hash=:voucherHash and occurred_at>=:from and "
            + "((:phoneHash is not null and phone_hash=:phoneHash) "
            + "or (:addressHash is not null and address_hash=:addressHash))", nativeQuery = true)
    long countDistinctAccounts(@Param("voucherHash") String voucherHash,
                               @Param("phoneHash") String phoneHash,
                               @Param("addressHash") String addressHash,
                               @Param("from") Instant from);

    @Modifying
    @Query(value = "delete from risk_correlations where occurred_at<:before", nativeQuery = true)
    int deleteExpired(@Param("before") Instant before);
}
