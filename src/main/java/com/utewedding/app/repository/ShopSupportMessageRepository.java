package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.SupportMessageEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Message queries always include the shop tenant and the public audience. */
public interface ShopSupportMessageRepository extends JpaRepository<SupportMessageEntity, UUID> {
    Page<SupportMessageEntity> findByTicketIdAndShopIdAndVisibilityOrderByCreatedAtDescIdDesc(
            UUID ticketId, UUID shopId, String visibility, Pageable pageable);

    List<SupportMessageEntity> findByTicketIdAndShopIdAndVisibilityOrderByCreatedAtAscIdAsc(
            UUID ticketId, UUID shopId, String visibility);

    java.util.Optional<SupportMessageEntity> findFirstByTicketIdAndShopIdAndVisibilityOrderByCreatedAtDescIdDesc(
            UUID ticketId, UUID shopId, String visibility);

    Optional<SupportMessageEntity> findByIdAndTicketIdAndShopIdAndVisibility(
            UUID id, UUID ticketId, UUID shopId, String visibility);
}
