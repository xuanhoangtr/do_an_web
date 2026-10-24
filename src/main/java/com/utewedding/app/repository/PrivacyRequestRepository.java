package com.utewedding.app.repository;

import com.utewedding.app.domain.PrivacyRequestStatus;
import com.utewedding.app.domain.PrivacyRequestType;
import com.utewedding.app.domain.entity.PrivacyRequestEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrivacyRequestRepository extends JpaRepository<PrivacyRequestEntity, UUID> {
    List<PrivacyRequestEntity> findByUserIdOrderByRequestedAtDesc(UUID userId);
    Optional<PrivacyRequestEntity> findFirstByUserIdAndRequestTypeAndStatusInOrderByRequestedAtDesc(
            UUID userId, PrivacyRequestType requestType, Collection<PrivacyRequestStatus> statuses);
    Page<PrivacyRequestEntity> findAllByOrderByRequestedAtDesc(Pageable pageable);
    Page<PrivacyRequestEntity> findByStatusOrderByRequestedAtAsc(PrivacyRequestStatus status, Pageable pageable);
    @Query("""
            select r from PrivacyRequestEntity r
            where (:type is null or r.requestType = :type)
              and (:status is null or r.status = :status)
            order by r.requestedAt desc
            """)
    Page<PrivacyRequestEntity> searchAdmin(@Param("type") PrivacyRequestType type,
                                           @Param("status") PrivacyRequestStatus status,
                                           Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PrivacyRequestEntity r where r.id = :id")
    Optional<PrivacyRequestEntity> findByIdForUpdate(@Param("id") UUID id);
}
