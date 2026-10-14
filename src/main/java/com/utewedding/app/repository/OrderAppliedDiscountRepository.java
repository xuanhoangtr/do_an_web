package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderAppliedDiscountEntity;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Permanent source for voucher use history, including orders whose quota was released. */
public interface OrderAppliedDiscountRepository extends JpaRepository<OrderAppliedDiscountEntity, UUID> {
    long countByVoucherId(UUID voucherId);
    List<OrderAppliedDiscountEntity> findAllByOrderIdOrderBySlotAsc(UUID orderId);

    @Query(value = """
            select new com.utewedding.app.repository.PlatformSubsidyCandidate(
                o.id, o.orderNumber, o.shopId, sum(d.discountAmount))
            from OrderAppliedDiscountEntity d, OrderEntity o
            where d.orderId = o.id
              and d.fundingSource = :fundingSource
              and d.voucherScope = :scope
              and d.voucherType in :merchandiseTypes
              and exists (select s.id from ShopOrderFinancialSnapshotEntity s
                          where s.orderId = o.id and s.shopId = o.shopId)
              and not exists (select a.id from PlatformFundReceiptAllocationEntity a
                              where a.orderId = o.id
                                and a.sourceKind = com.utewedding.app.domain.PlatformFundReceiptSourceKind.OTHER
                                and a.sourceReference = 'PLATFORM_SUBSIDY')
            group by o.id, o.orderNumber, o.shopId
            order by max(d.createdAt) desc, o.id
            """,
            countQuery = """
            select count(distinct o.id)
            from OrderAppliedDiscountEntity d, OrderEntity o
            where d.orderId = o.id
              and d.fundingSource = :fundingSource
              and d.voucherScope = :scope
              and d.voucherType in :merchandiseTypes
              and exists (select s.id from ShopOrderFinancialSnapshotEntity s
                          where s.orderId = o.id and s.shopId = o.shopId)
              and not exists (select a.id from PlatformFundReceiptAllocationEntity a
                              where a.orderId = o.id
                                and a.sourceKind = com.utewedding.app.domain.PlatformFundReceiptSourceKind.OTHER
                                and a.sourceReference = 'PLATFORM_SUBSIDY')
            """)
    Page<PlatformSubsidyCandidate> findPlatformMerchandiseSubsidies(
            @Param("fundingSource") VoucherFundingSource fundingSource,
            @Param("scope") VoucherScope scope,
            @Param("merchandiseTypes") List<VoucherType> merchandiseTypes,
            Pageable pageable);
}
