package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.EmailVerificationChallengeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface EmailVerificationChallengeRepository
        extends JpaRepository<EmailVerificationChallengeEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailVerificationChallengeEntity c "
            + "where lower(c.email) = lower(:email) and c.consumedAt is null "
            + "order by c.createdAt desc")
    Optional<EmailVerificationChallengeEntity> findActiveForEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailVerificationChallengeEntity c "
            + "where c.userId = :userId and c.consumedAt is null "
            + "order by c.createdAt desc")
    Optional<EmailVerificationChallengeEntity> findActiveForUserForUpdate(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationChallengeEntity> findByLinkTokenHashAndConsumedAtIsNull(String linkTokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailVerificationChallengeEntity c set c.consumedAt = :consumedAt "
            + "where c.userId = :userId and c.consumedAt is null")
    int consumeActiveForUser(@Param("userId") UUID userId,
                             @Param("consumedAt") java.time.Instant consumedAt);
}
