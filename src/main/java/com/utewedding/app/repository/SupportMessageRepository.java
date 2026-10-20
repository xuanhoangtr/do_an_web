package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.SupportMessageEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupportMessageRepository extends JpaRepository<SupportMessageEntity, UUID> {
    List<SupportMessageEntity> findByTicketIdOrderByCreatedAtAscIdAsc(UUID id);
    List<SupportMessageEntity> findByTicketIdAndVisibilityOrderByCreatedAtAscIdAsc(UUID id, String visibility);

    Page<SupportMessageEntity> findByTicketIdAndVisibilityInOrderByCreatedAtDescIdDesc(
            UUID ticketId, java.util.Collection<String> visibility, Pageable pageable);

    @Query("select m from SupportMessageEntity m where m.ticketId in :ticketIds order by m.createdAt asc, m.id asc")
    List<SupportMessageEntity> findByTicketIdInOrderByCreatedAtAscIdAsc(@Param("ticketIds") Collection<UUID> ticketIds);
}
