package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.SupportTicketParticipantEntity;
import com.utewedding.app.domain.entity.SupportTicketParticipantId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Participants are listed only with the tenant key in shop chat operations. */
public interface ShopSupportParticipantRepository extends JpaRepository<SupportTicketParticipantEntity,
        SupportTicketParticipantId> {
    List<SupportTicketParticipantEntity> findByShopIdAndIdTicketIdOrderByJoinedAtAsc(UUID shopId, UUID ticketId);

    boolean existsByShopIdAndIdTicketIdAndIdUserId(UUID shopId, UUID ticketId, UUID userId);
}
