package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.AuthSessionEntity;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {

    Optional<AuthSessionEntity> findByRefreshTokenHash(String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSessionEntity s where s.refreshTokenHash = :hash")
    Optional<AuthSessionEntity> findByRefreshTokenHashForUpdate(@Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSessionEntity s set s.revokedAt = :now, s.revokedReason = :reason "
            + "where s.familyId = :familyId and s.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("now") Instant now,
                     @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSessionEntity s set s.revokedAt = :now, s.revokedReason = :reason "
            + "where s.userId = :userId and s.revokedAt is null")
    int revokeAllActive(@Param("userId") UUID userId,
                        @Param("now") Instant now,
                        @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSessionEntity s set s.revokedAt = :now, s.revokedReason = :reason "
            + "where s.userId = :userId and s.revokedAt is null and s.id <> :keepId")
    int revokeAllActiveExcept(@Param("userId") UUID userId,
                              @Param("keepId") UUID keepId,
                              @Param("now") Instant now,
                              @Param("reason") String reason);

    java.util.List<AuthSessionEntity> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID userId);

        boolean existsByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(UUID id, UUID userId, Instant now);
}
