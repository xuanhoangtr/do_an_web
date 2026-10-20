package com.utewedding.app.repository;import com.utewedding.app.domain.*;import com.utewedding.app.domain.entity.SupportTicketEntity;import jakarta.persistence.LockModeType;import java.time.Instant;import java.util.*;import org.springframework.data.jpa.repository.*;import org.springframework.data.repository.query.Param;
public interface SupportTicketRepository extends JpaRepository<SupportTicketEntity,UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<SupportTicketEntity>{List<SupportTicketEntity> findByCustomerIdOrderByLastActivityAtDesc(UUID id);@Lock(LockModeType.PESSIMISTIC_WRITE)@Query("select t from SupportTicketEntity t where t.id=:id")Optional<SupportTicketEntity> findByIdForUpdate(@Param("id")UUID id);@Modifying@Query("update SupportTicketEntity t set t.status=com.utewedding.app.domain.SupportTicketStatus.CLOSED,t.closedAt=:now where t.status=com.utewedding.app.domain.SupportTicketStatus.RESOLVED and t.resolvedAt<:cutoff")int autoClose(@Param("cutoff")Instant cutoff,@Param("now")Instant now);List<SupportTicketEntity> findFirst100ByStatusNotOrderByPriorityDescLastActivityAtAsc(SupportTicketStatus status);List<SupportTicketEntity> findFirst100ByTicketKindAndStatusNotOrderByPriorityDescLastActivityAtAsc(SupportTicketKind kind, SupportTicketStatus status);
 @Query(value="""
 select count(*) from support_tickets t
 where t.status not in ('RESOLVED','CLOSED')
   and exists (
     select 1 from support_messages m
     where m.ticket_id=t.id and m.visibility='CUSTOMER' and m.author_id=t.customer_id
       and not exists (
         select 1 from support_messages newer
         where newer.ticket_id=m.ticket_id and newer.visibility='CUSTOMER'
           and (newer.created_at>m.created_at or (newer.created_at=m.created_at and newer.id>m.id))
       )
   )
 """,nativeQuery=true)
 long countNeedsStaffReply();
}
