package com.utewedding.app.repository;

import com.utewedding.app.domain.SupportTicketKind;
import com.utewedding.app.domain.entity.SupportTicketEntity;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository entry points that always constrain a shop conversation to its tenant. */
public interface ShopSupportTicketRepository extends JpaRepository<SupportTicketEntity, UUID> {
    Page<SupportTicketEntity> findByTicketKindAndShopIdAndCustomerIdOrderByLastActivityAtDesc(
            SupportTicketKind ticketKind, UUID shopId, UUID customerId, Pageable pageable);

    Page<SupportTicketEntity> findByTicketKindAndShopIdOrderByLastActivityAtDesc(
            SupportTicketKind ticketKind, UUID shopId, Pageable pageable);

    Optional<SupportTicketEntity> findByIdAndTicketKindAndShopIdAndCustomerId(
            UUID id, SupportTicketKind ticketKind, UUID shopId, UUID customerId);

    Optional<SupportTicketEntity> findByIdAndTicketKindAndShopId(
            UUID id, SupportTicketKind ticketKind, UUID shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicketEntity t where t.id = :id and t.ticketKind = :kind "
            + "and t.shopId = :shopId and t.customerId = :customerId")
    Optional<SupportTicketEntity> findCustomerTicketForUpdate(@Param("id") UUID id,
            @Param("kind") SupportTicketKind kind, @Param("shopId") UUID shopId,
            @Param("customerId") UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicketEntity t where t.id = :id and t.ticketKind = :kind "
            + "and t.shopId = :shopId")
    Optional<SupportTicketEntity> findShopTicketForUpdate(@Param("id") UUID id,
            @Param("kind") SupportTicketKind kind, @Param("shopId") UUID shopId);
}
