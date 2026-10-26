package com.utewedding.app.repository;
import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ManagedMediaObjectRepository extends JpaRepository<ManagedMediaObjectEntity,UUID>{
    @Query(value="select * from managed_media_objects where status in (:statuses) and legal_hold=false and (retain_until is null or retain_until<=:now) and (next_retry_at is null or next_retry_at<=:now) order by created_at,id for update skip locked limit :limit",nativeQuery=true)
    List<ManagedMediaObjectEntity> claimCleanup(@Param("statuses") List<String> statuses,@Param("now") Instant now,@Param("limit") int limit);
    List<ManagedMediaObjectEntity> findByOwnerIdAndStatusNot(UUID ownerId, ManagedMediaStatus status);
    Optional<ManagedMediaObjectEntity> findByIdAndShopIdAndPurposeAndStatusAndDeliveryType(
            UUID id, UUID shopId, String purpose, ManagedMediaStatus status, String deliveryType);
    Optional<ManagedMediaObjectEntity> findByIdAndShopIdAndPurposeAndStatusAndDeliveryTypeAndOwnerId(
            UUID id, UUID shopId, String purpose, ManagedMediaStatus status, String deliveryType, UUID ownerId);
    List<ManagedMediaObjectEntity> findByShopIdAndPurposeAndStatusAndDeliveryTypeOrderByCreatedAtDesc(
            UUID shopId, String purpose, ManagedMediaStatus status, String deliveryType);
    long countByStatus(ManagedMediaStatus status);
}
