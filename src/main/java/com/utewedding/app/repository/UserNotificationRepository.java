package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.UserNotificationEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, UUID> {
    java.util.List<UserNotificationEntity> findAllByRecipientIdOrderByCreatedAtDesc(UUID recipientId);
    long deleteByRecipientId(UUID recipientId);
    Page<UserNotificationEntity> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(UUID recipientId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserNotificationEntity n set n.readAt = :now where n.recipientId = :recipientId and n.readAt is null")
    int markAllRead(@Param("recipientId") UUID recipientId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserNotificationEntity n set n.readAt = :now "
            + "where n.id = :id and n.recipientId = :recipientId and n.readAt is null")
    int markRead(@Param("id") UUID id, @Param("recipientId") UUID recipientId, @Param("now") Instant now);
}
