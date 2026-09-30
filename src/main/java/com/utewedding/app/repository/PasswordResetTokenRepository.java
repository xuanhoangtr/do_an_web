package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PasswordResetTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import jakarta.persistence.LockModeType;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {
    @Query("select t.userId from PasswordResetTokenEntity t where t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetTokenEntity t where t.tokenHash = :tokenHash")
    Optional<PasswordResetTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetTokenEntity t set t.usedAt = :usedAt "
            + "where t.userId = :userId and t.usedAt is null")
    int invalidateActiveForUser(@Param("userId") UUID userId,
                                @Param("usedAt") java.time.Instant usedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetTokenEntity> findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDescIdDesc(UUID userId);

    default Optional<PasswordResetTokenEntity> findLatestActiveForUser(UUID userId) {
        return findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDescIdDesc(userId);
    }
}
