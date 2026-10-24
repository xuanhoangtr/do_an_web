package com.utewedding.app.repository;

import com.utewedding.app.domain.OutboxEventStatus;
import com.utewedding.app.domain.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    Optional<OutboxEventEntity> findByDeduplicationKey(String key);
        @Query(value = "select * from outbox_events where status in (:statuses) and next_attempt_at <= :now "
            + "and (lease_until is null or lease_until <= :now) "
            + "order by next_attempt_at, created_at, id for update skip locked limit :limit", nativeQuery = true)
        List<OutboxEventEntity> findDueForClaim(@Param("statuses") Collection<String> statuses,
                            @Param("now") Instant now, @Param("limit") int limit);
}