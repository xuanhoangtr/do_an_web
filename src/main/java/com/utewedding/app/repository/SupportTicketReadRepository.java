package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.SupportTicketReadEntity;
import com.utewedding.app.domain.entity.SupportTicketReadId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportTicketReadRepository extends JpaRepository<SupportTicketReadEntity, SupportTicketReadId> {
    @Modifying
    @Query(value = """
            INSERT INTO support_ticket_reads (ticket_id, user_id, shop_id, last_read_at)
            SELECT t.id, :userId, t.shop_id, :readAt
            FROM support_tickets t
            WHERE t.id = :ticketId
            ON CONFLICT (ticket_id, user_id) DO UPDATE
            SET last_read_at = GREATEST(support_ticket_reads.last_read_at, EXCLUDED.last_read_at)
            WHERE support_ticket_reads.shop_id IS NOT DISTINCT FROM EXCLUDED.shop_id
            """, nativeQuery = true)
    void markRead(@Param("ticketId") UUID ticketId, @Param("userId") UUID userId,
                  @Param("readAt") java.time.Instant readAt);

    @Query(value = """
            SELECT COUNT(DISTINCT t.id)
            FROM support_tickets t
            WHERE t.ticket_kind = 'PLATFORM_SUPPORT' AND t.customer_id = :userId AND t.status <> 'CLOSED'
              AND EXISTS (
                  SELECT 1 FROM support_messages m
                  WHERE m.ticket_id = t.id AND m.visibility = 'CUSTOMER'
                    AND m.author_id IS NOT NULL AND m.author_id <> t.customer_id
                    AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                      WHERE r.ticket_id = t.id AND r.user_id = :userId), TIMESTAMPTZ 'epoch')
              )
            """, nativeQuery = true)
    long countCustomerUnread(@Param("userId") UUID userId);

    @Query(value = """
            SELECT COUNT(DISTINCT t.id)
            FROM support_tickets t
            WHERE t.status NOT IN ('RESOLVED','CLOSED')
              AND EXISTS (
                  SELECT 1 FROM support_messages m
                  WHERE m.ticket_id = t.id AND m.visibility = 'CUSTOMER'
                    AND m.author_id = t.customer_id
                    AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                      WHERE r.ticket_id = t.id AND r.user_id = :userId), TIMESTAMPTZ 'epoch')
              )
            """, nativeQuery = true)
    long countStaffUnread(@Param("userId") UUID userId);

    @Query(value = """
            SELECT COUNT(*) FROM support_messages m
            WHERE m.ticket_id = :ticketId AND m.visibility = 'CUSTOMER'
              AND m.author_id IS NOT NULL AND m.author_id <> :ownerId
              AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                WHERE r.ticket_id = :ticketId AND r.user_id = :ownerId), TIMESTAMPTZ 'epoch')
            """, nativeQuery = true)
    long countCustomerUnreadForTicket(@Param("ticketId") UUID ticketId, @Param("ownerId") UUID ownerId);

    @Query(value = """
            SELECT COUNT(*) FROM support_messages m
            WHERE m.ticket_id = :ticketId AND m.visibility = 'CUSTOMER' AND m.author_id = :customerId
              AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                WHERE r.ticket_id = :ticketId AND r.user_id = :staffId), TIMESTAMPTZ 'epoch')
            """, nativeQuery = true)
    long countStaffUnreadForTicket(@Param("ticketId") UUID ticketId,
                                   @Param("customerId") UUID customerId,
                                   @Param("staffId") UUID staffId);
}
