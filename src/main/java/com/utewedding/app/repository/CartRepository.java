package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CartEntity;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<CartEntity, UUID> {
    Optional<CartEntity> findByUserId(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CartEntity c where c.userId = :userId")
    Optional<CartEntity> findByUserIdForUpdate(@Param("userId") UUID userId);
    long deleteByUserId(UUID userId);
}
