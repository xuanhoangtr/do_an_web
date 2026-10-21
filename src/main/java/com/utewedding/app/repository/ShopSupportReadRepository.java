package com.utewedding.app.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Shop-keyed read cursors and unread counts; never falls back to the global staff queue. */
public interface ShopSupportReadRepository extends JpaRepository<com.utewedding.app.domain.entity.SupportTicketReadEntity,
        com.utewedding.app.domain.entity.SupportTicketReadId> {
    @Modifying
    @Query(value = """
            INSERT INTO support_ticket_reads (ticket_id, user_id, shop_id, last_read_at)
            VALUES (:ticketId, :userId, :shopId, :readAt)
            ON CONFLICT (ticket_id, user_id) DO UPDATE
            SET last_read_at = GREATEST(support_ticket_reads.last_read_at, EXCLUDED.last_read_at)
            WHERE support_ticket_reads.shop_id = EXCLUDED.shop_id
            """, nativeQuery = true)
    void markShopRead(@Param("ticketId") UUID ticketId, @Param("userId") UUID userId,
                      @Param("shopId") UUID shopId, @Param("readAt") Instant readAt);

    @Query(value = """
            SELECT COUNT(DISTINCT t.id)
            FROM support_tickets t
            WHERE t.ticket_kind = 'SHOP' AND t.shop_id = :shopId AND t.customer_id = :userId
              AND t.status <> 'CLOSED'
              AND EXISTS (
                  SELECT 1 FROM support_messages m
                  WHERE m.ticket_id = t.id AND m.shop_id = t.shop_id AND m.visibility = 'CUSTOMER'
                    AND m.author_id IS NOT NULL AND m.author_id <> t.customer_id
                    AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                      WHERE r.ticket_id = t.id AND r.user_id = :userId AND r.shop_id = t.shop_id), TIMESTAMPTZ 'epoch')
              )
            """, nativeQuery = true)
    long countCustomerUnread(@Param("shopId") UUID shopId, @Param("userId") UUID userId);

    @Query(value = """
            SELECT COUNT(DISTINCT t.id)
            FROM support_tickets t
            WHERE t.ticket_kind = 'SHOP' AND t.shop_id = :shopId AND t.status <> 'CLOSED'
              AND EXISTS (
                  SELECT 1 FROM support_messages m
                  WHERE m.ticket_id = t.id AND m.shop_id = t.shop_id AND m.visibility = 'CUSTOMER'
                    AND m.author_id = t.customer_id
                    AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                      WHERE r.ticket_id = t.id AND r.user_id = :userId AND r.shop_id = t.shop_id), TIMESTAMPTZ 'epoch')
              )
            """, nativeQuery = true)
    long countVendorUnread(@Param("shopId") UUID shopId, @Param("userId") UUID userId);

    @Query(value = """
            SELECT COUNT(*) FROM support_messages m
            WHERE m.ticket_id = :ticketId AND m.shop_id = :shopId AND m.visibility = 'CUSTOMER'
              AND m.author_id IS NOT NULL AND m.author_id <> :customerId
              AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                WHERE r.ticket_id = :ticketId AND r.user_id = :customerId AND r.shop_id = :shopId), TIMESTAMPTZ 'epoch')
            """, nativeQuery = true)
    long countCustomerUnreadForTicket(@Param("ticketId") UUID ticketId, @Param("shopId") UUID shopId,
                                      @Param("customerId") UUID customerId);

    @Query(value = """
            SELECT COUNT(*) FROM support_messages m
            WHERE m.ticket_id = :ticketId AND m.shop_id = :shopId AND m.visibility = 'CUSTOMER'
              AND m.author_id = :customerId
              AND m.created_at > COALESCE((SELECT r.last_read_at FROM support_ticket_reads r
                WHERE r.ticket_id = :ticketId AND r.user_id = :vendorId AND r.shop_id = :shopId), TIMESTAMPTZ 'epoch')
            """, nativeQuery = true)
    long countVendorUnreadForTicket(@Param("ticketId") UUID ticketId, @Param("shopId") UUID shopId,
                                    @Param("customerId") UUID customerId, @Param("vendorId") UUID vendorId);
}
