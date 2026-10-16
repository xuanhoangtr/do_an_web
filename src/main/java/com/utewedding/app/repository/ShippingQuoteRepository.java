package com.utewedding.app.repository;
import com.utewedding.app.domain.entity.ShippingQuoteEntity;
import java.util.Optional; import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface ShippingQuoteRepository extends JpaRepository<ShippingQuoteEntity, UUID> {
    Optional<ShippingQuoteEntity> findByIdAndCustomerId(UUID id, UUID customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ShippingQuoteEntity q where q.id = :id and q.customerId = :customerId")
    Optional<ShippingQuoteEntity> findByIdAndCustomerIdForUpdate(@Param("id") UUID id,
                                                                 @Param("customerId") UUID customerId);
}
