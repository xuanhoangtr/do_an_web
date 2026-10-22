package com.utewedding.app.repository;
import com.utewedding.app.domain.entity.CodReconciliationEntity; import java.util.*;
import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface CodReconciliationRepository extends JpaRepository<CodReconciliationEntity,UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<CodReconciliationEntity>{
 Optional<CodReconciliationEntity> findByOrderId(UUID orderId);
 List<CodReconciliationEntity> findByOrderIdIn(Collection<UUID> orderIds);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select c from CodReconciliationEntity c where c.orderId=:orderId") Optional<CodReconciliationEntity> findByOrderIdForUpdate(@Param("orderId") UUID orderId);
}
