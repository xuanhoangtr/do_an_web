package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.SupportTicketParticipantEntity;
import com.utewedding.app.domain.entity.SupportTicketParticipantId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketParticipantRepository extends JpaRepository<SupportTicketParticipantEntity, SupportTicketParticipantId> {
    List<SupportTicketParticipantEntity> findByIdTicketIdOrderByJoinedAtAsc(UUID ticketId);
    boolean existsByIdTicketIdAndIdUserId(UUID ticketId, UUID userId);
}
