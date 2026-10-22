package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ReturnCaseEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnCaseRepository extends JpaRepository<ReturnCaseEntity, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<ReturnCaseEntity> {
    long countByStatusIn(java.util.Collection<com.utewedding.app.domain.ReturnCaseStatus> statuses);
    boolean existsByOrderIdAndStatusIn(UUID orderId, java.util.Collection<com.utewedding.app.domain.ReturnCaseStatus> statuses);
    @Query("select c.id from ReturnCaseEntity c where c.status = :status and c.shipDeadline <= :now order by c.shipDeadline, c.id")
    List<UUID> findExpiredCaseIds(@Param("status") com.utewedding.app.domain.ReturnCaseStatus status,
                                  @Param("now") java.time.Instant now);
    java.util.List<ReturnCaseEntity> findByOrderIdAndCustomerIdOrderByCreatedAtDesc(UUID orderId,UUID customerId);
    long countByCustomerIdAndCreatedAtGreaterThanEqual(UUID customerId, java.time.Instant from);
    List<ReturnCaseEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    Optional<ReturnCaseEntity> findByIdAndCustomerId(UUID id, UUID customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReturnCaseEntity c where c.id = :id")
    Optional<ReturnCaseEntity> findByIdForUpdate(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReturnCaseEntity c where c.id = :id and c.customerId = :customerId")
    Optional<ReturnCaseEntity> findByIdAndCustomerIdForUpdate(@Param("id") UUID id, @Param("customerId") UUID customerId);
}
