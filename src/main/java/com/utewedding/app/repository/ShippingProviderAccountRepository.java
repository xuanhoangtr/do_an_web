package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShippingProviderAccountEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShippingProviderAccountRepository extends JpaRepository<ShippingProviderAccountEntity, UUID> {
    List<ShippingProviderAccountEntity> findByProviderCodeAndEnvironmentOrderByAccountNameAsc(
            String providerCode, String environment);
    Optional<ShippingProviderAccountEntity> findByIdAndProviderCode(UUID id, String providerCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from ShippingProviderAccountEntity account "
            + "where account.id = :id and account.providerCode = :providerCode")
    Optional<ShippingProviderAccountEntity> findByIdAndProviderCodeForUpdate(
            @Param("id") UUID id, @Param("providerCode") String providerCode);
}
