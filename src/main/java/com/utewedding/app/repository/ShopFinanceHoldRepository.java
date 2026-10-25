package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopFinanceHoldSourceType;
import com.utewedding.app.domain.ShopFinanceHoldStatus;
import com.utewedding.app.domain.entity.ShopFinanceHoldEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopFinanceHoldRepository extends JpaRepository<ShopFinanceHoldEntity, UUID> {
    Optional<ShopFinanceHoldEntity> findByShopIdAndSourceTypeAndSourceId(
            UUID shopId, ShopFinanceHoldSourceType sourceType, UUID sourceId);

    List<ShopFinanceHoldEntity> findByShopIdAndOrderIdAndStatusOrderByCreatedAtAsc(
            UUID shopId, UUID orderId, ShopFinanceHoldStatus status);

    @Query("select coalesce(sum(hold.amount), 0) from ShopFinanceHoldEntity hold "
            + "where hold.shopId = :shopId and hold.orderId = :orderId and hold.status = :status")
    long sumAmountByShopAndOrderAndStatus(@Param("shopId") UUID shopId,
                                          @Param("orderId") UUID orderId,
                                          @Param("status") ShopFinanceHoldStatus status);
}
