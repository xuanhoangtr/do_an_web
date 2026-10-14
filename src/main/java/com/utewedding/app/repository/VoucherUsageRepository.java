package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.VoucherUsageEntity;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherUsageRepository extends JpaRepository<VoucherUsageEntity, UUID> {
    long countByVoucherId(UUID voucherId);
    @Query(value = "select coalesce(max(usage_count), 0) from "
            + "(select count(*) as usage_count from voucher_usages "
            + "where voucher_id = :voucherId group by user_id) grouped", nativeQuery = true)
    long maxUsageCountByVoucherId(@Param("voucherId") UUID voucherId);
    long countByVoucherIdAndUserId(UUID voucherId, UUID userId);
    boolean existsByOrderId(UUID orderId);
    Optional<VoucherUsageEntity> findByOrderId(UUID orderId);
}
