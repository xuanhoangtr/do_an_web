package com.utewedding.app.repository;
import com.utewedding.app.domain.entity.CodObligationEntity; import java.util.*;
import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface CodObligationRepository extends JpaRepository<CodObligationEntity,UUID>{
 Optional<CodObligationEntity> findByFulfillmentId(UUID fulfillmentId);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select o from CodObligationEntity o where o.fulfillmentId=:id") Optional<CodObligationEntity> findByFulfillmentIdForUpdate(@Param("id") UUID fulfillmentId);
 List<CodObligationEntity> findTop100ByRemittanceStatusOrderByCollectedAtAsc(com.utewedding.app.domain.CodRemittanceStatus status);
 List<CodObligationEntity> findByReconciliationIdOrderByFulfillmentIdAsc(UUID reconciliationId);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select o from CodObligationEntity o where o.reconciliationId=:id order by o.fulfillmentId") List<CodObligationEntity> findByReconciliationIdForUpdate(@Param("id") UUID id);
}
