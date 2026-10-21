package com.utewedding.app.service.impl;

import com.utewedding.app.config.CloudinaryConfig;
import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.SupportTicketStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.CustomerShopChatContactEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CustomerShopChatContactRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.security.AppRole;
import com.utewedding.app.security.UserPrincipal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Customer-facing mixed inbox and explicit shop-contact persistence. */
@Service
public class CustomerMessagesService {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGE = 10_000;
    private static final Duration SUPPORT_REPLY_WINDOW = Duration.ofDays(7);

    private final MarketplaceFeatureFlags features;
    private final CustomerShopChatContactRepository contacts;
    private final ShopRepository shops;
    private final ProductRepository products;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final CloudinaryConfig cloudinary;

    public CustomerMessagesService(MarketplaceFeatureFlags features,
            CustomerShopChatContactRepository contacts, ShopRepository shops,
            ProductRepository products, UserRepository users, JdbcTemplate jdbc,
            CloudinaryConfig cloudinary) {
        this.features = features;
        this.contacts = contacts;
        this.shops = shops;
        this.products = products;
        this.users = users;
        this.jdbc = jdbc;
        this.cloudinary = cloudinary;
    }

    @Transactional
    public OpenContactResponse openShopContact(UserPrincipal actor, UUID shopId, UUID productId) {
        requireShopChatEnabled();
        UserEntity customer = requireCustomer(actor);
        ShopEntity shop = requireShop(shopId, true);
        if (productId != null) {
            products.findByIdAndShopId(productId, shop.getId())
                    .filter(ProductEntity::isActive)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        Instant now = Instant.now();
        contacts.upsertOpened(UUID.randomUUID(), customer.getId(), shop.getId(), now);
        CustomerShopChatContactEntity contact = contacts.findByCustomerAndShopForUpdate(customer.getId(), shop.getId())
                .orElseThrow(() -> new IllegalStateException("Opened contact was not persisted."));

        LatestConversation latest = latestShopConversation(customer.getId(), shop.getId());
        boolean canSend = shop.getStatus() == ShopStatus.ACTIVE && hasActiveOwner(shop.getId());
        return new OpenContactResponse(contact.getId(), shop.getId(), shop.getSlug(), shop.getDisplayName(),
                publicShopLogo(shop.getId()), latest == null ? null : latest.conversationId(),
                latest != null && latest.hasMessages(), contact.getLastOpenedAt(), canSend, shop.getStatus().name());
    }

    @Transactional(readOnly = true)
    public InboxPage inbox(UserPrincipal actor, InboxScope scope, int requestedPage) {
        UserEntity customer = requireCustomer(actor);
        InboxScope safeScope = scope == null ? InboxScope.ALL : scope;
        int page = clampPage(requestedPage);
        int offset = page * PAGE_SIZE;

        List<Object> params = new ArrayList<>();
        String sql = inboxSql(safeScope, params, customer.getId());
        params.add(PAGE_SIZE);
        params.add(offset);
        List<InboxItem> items = jdbc.query(sql, this::mapInboxItem, params.toArray());

        long total = countInboxRows(safeScope, customer.getId());
        return new InboxPage(items, page, (long) offset + items.size() < total, total);
    }

    /**
     * Resolves one durable shop contact without relying on inbox pagination. The
     * customer id remains a server-side predicate so a guessed contact id cannot
     * disclose another customer's shop or conversation metadata.
     */
    @Transactional(readOnly = true)
    public InboxItem contact(UserPrincipal actor, UUID contactId) {
        UserEntity customer = requireCustomer(actor);
        if (contactId == null) throw new ResourceNotFoundException("Không tìm thấy cuộc trò chuyện.");
        String sql = "SELECT scope, contact_id, shop_id, shop_slug, conversation_id, title, avatar_url, preview, "
                + "last_activity_at, unread_conversations, status, has_messages, shop_status, owner_available, "
                + "resolved_at FROM (" + shopInboxSelect() + " AND c.id = ?) inbox_entries";
        List<InboxItem> rows = jdbc.query(sql, this::mapInboxItem, customer.getId(), contactId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy cuộc trò chuyện.");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public UnreadSummary unread(UserPrincipal actor) {
        UserEntity customer = requireCustomer(actor);
        long[] counts = jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM support_tickets t
                     WHERE t.ticket_kind = 'SHOP' AND t.customer_id = ? AND t.status <> 'CLOSED'
                       AND EXISTS (SELECT 1 FROM customer_shop_chat_contacts c
                                   WHERE c.customer_id = t.customer_id AND c.shop_id = t.shop_id)
                       AND EXISTS (SELECT 1 FROM support_messages m
                                   WHERE m.ticket_id = t.id AND m.shop_id = t.shop_id
                                     AND m.visibility = 'CUSTOMER' AND m.author_id IS NOT NULL
                                     AND m.author_id <> t.customer_id
                                     AND m.created_at > COALESCE((SELECT r.last_read_at
                                         FROM support_ticket_reads r WHERE r.ticket_id = t.id
                                           AND r.user_id = t.customer_id AND r.shop_id = t.shop_id),
                                         TIMESTAMPTZ 'epoch'))) AS shop,
                    (SELECT COUNT(*) FROM support_tickets t
                     WHERE t.ticket_kind = 'PLATFORM_SUPPORT' AND t.customer_id = ? AND t.status <> 'CLOSED'
                       AND EXISTS (SELECT 1 FROM support_messages m
                                   WHERE m.ticket_id = t.id AND m.visibility = 'CUSTOMER'
                                     AND m.author_id IS NOT NULL AND m.author_id <> t.customer_id
                                     AND m.created_at > COALESCE((SELECT r.last_read_at
                                         FROM support_ticket_reads r WHERE r.ticket_id = t.id
                                           AND r.user_id = t.customer_id), TIMESTAMPTZ 'epoch'))) AS platform_support
                """, (rs, rowNum) -> new long[] {rs.getLong("shop"), rs.getLong("platform_support")},
                customer.getId(), customer.getId());
        long shop = counts == null ? 0 : counts[0];
        long support = counts == null ? 0 : counts[1];
        return new UnreadSummary(shop + support, shop, support);
    }

    /** Called inside the legacy create transaction; serializes first sends by customer/shop. */
    @Transactional
    public void lockForNewConversation(UUID customerId, UUID shopId) {
        contacts.upsertOpened(UUID.randomUUID(), customerId, shopId, Instant.now());
        contacts.findByCustomerAndShopForUpdate(customerId, shopId)
                .orElseThrow(() -> new IllegalStateException("Contact lock was not acquired."));
    }

    @Transactional
    public void requireNewConversationAllowed(UUID customerId, UUID shopId) {
        Boolean hasReplyableConversation = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM support_tickets
                WHERE ticket_kind = 'SHOP' AND customer_id = ? AND shop_id = ?
                  AND (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER')
                       OR (status = 'RESOLVED' AND resolved_at >= CURRENT_TIMESTAMP - INTERVAL '7 days')))
                """, Boolean.class, customerId, shopId);
        if (Boolean.TRUE.equals(hasReplyableConversation)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                    "A conversation is already open for this shop. Reload the inbox and reply to it.");
        }
    }

    /** Links legacy ticket creation to the durable customer/shop contact in the same transaction. */
    @Transactional
    public void linkNewConversation(UUID customerId, UUID shopId, UUID ticketId) {
        CustomerShopChatContactEntity contact = contacts.findByCustomerAndShopForUpdate(customerId, shopId)
                .orElseThrow(() -> new IllegalStateException("Shop contact must be locked before ticket creation."));
        contact.setLastTicketId(ticketId);
        contacts.save(contact);
    }

    /** Allows this customer to read retained history even when a shop is inactive or chat is disabled. */
    @Transactional(readOnly = true)
    public void requireExistingContact(UUID customerId, UUID shopId) {
        ShopEntity shop = shopId == null ? null : shops.findById(shopId).orElse(null);
        if (shop == null || shop.getKind() != ShopKind.VENDOR
                || !contacts.existsByCustomerIdAndShopId(customerId, shopId)) {
            throw new ResourceNotFoundException("Không tìm thấy cuộc trò chuyện.");
        }
    }

    @Transactional(readOnly = true)
    public boolean hasExistingContact(UUID customerId, UUID shopId) {
        ShopEntity shop = shopId == null ? null : shops.findById(shopId).orElse(null);
        if (shop == null || shop.getKind() != ShopKind.VENDOR) {
            throw new ResourceNotFoundException("Không tìm thấy cuộc trò chuyện.");
        }
        return contacts.existsByCustomerIdAndShopId(customerId, shopId);
    }

    private String inboxSql(InboxScope scope, List<Object> params, UUID customerId) {
        List<String> sources = new ArrayList<>();
        if (scope != InboxScope.PLATFORM_SUPPORT) {
            sources.add(shopInboxSelect());
            params.add(customerId);
        }
        if (scope != InboxScope.SHOP) {
            sources.add(platformInboxSelect());
            params.add(customerId);
        }
        return "SELECT scope, contact_id, shop_id, shop_slug, conversation_id, title, avatar_url, preview, "
                + "last_activity_at, unread_conversations, status, has_messages, shop_status, owner_available, "
                + "resolved_at FROM (" + String.join(" UNION ALL ", sources) + ") inbox_entries "
                + "ORDER BY sort_at DESC NULLS LAST, scope ASC, COALESCE(contact_id, conversation_id) ASC "
                + "LIMIT ? OFFSET ?";
    }

    private InboxItem mapInboxItem(ResultSet rs, int rowNum) throws SQLException {
        return new InboxItem(
                rs.getString("scope"), rs.getObject("contact_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getString("shop_slug"),
                rs.getObject("conversation_id", UUID.class), rs.getString("title"),
                publicShopLogo(rs.getString("avatar_url")), rs.getString("preview"),
                instant(rs.getTimestamp("last_activity_at")), rs.getLong("unread_conversations"),
                rs.getString("status"), rs.getBoolean("has_messages"),
                canSend(rs.getString("scope"), rs.getString("shop_status"),
                        rs.getBoolean("owner_available"), rs.getString("status"),
                        instant(rs.getTimestamp("resolved_at"))), rs.getString("shop_status"));
    }

    private String shopInboxSelect() {
        return """
                SELECT 'SHOP'::text AS scope, c.id AS contact_id, c.shop_id, s.slug AS shop_slug,
                       latest.id AS conversation_id, s.display_name AS title, logo.secure_url AS avatar_url,
                       latest_message.body AS preview, latest.last_activity_at,
                       (SELECT COUNT(*) FROM support_tickets unread_ticket
                        WHERE unread_ticket.ticket_kind = 'SHOP' AND unread_ticket.customer_id = c.customer_id
                          AND unread_ticket.shop_id = c.shop_id AND unread_ticket.status <> 'CLOSED'
                          AND EXISTS (SELECT 1 FROM support_messages unread_message
                                      WHERE unread_message.ticket_id = unread_ticket.id
                                        AND unread_message.shop_id = unread_ticket.shop_id
                                        AND unread_message.visibility = 'CUSTOMER'
                                        AND unread_message.author_id IS NOT NULL
                                        AND unread_message.author_id <> unread_ticket.customer_id
                                        AND unread_message.created_at > COALESCE((SELECT r.last_read_at
                                            FROM support_ticket_reads r WHERE r.ticket_id = unread_ticket.id
                                              AND r.user_id = c.customer_id AND r.shop_id = c.shop_id),
                                            TIMESTAMPTZ 'epoch'))) AS unread_conversations,
                       latest.status, EXISTS (SELECT 1 FROM support_tickets history_ticket
                            JOIN support_messages history_message ON history_message.ticket_id = history_ticket.id
                            WHERE history_ticket.ticket_kind = 'SHOP' AND history_ticket.customer_id = c.customer_id
                              AND history_ticket.shop_id = c.shop_id
                              AND history_message.shop_id = c.shop_id AND history_message.visibility = 'CUSTOMER') AS has_messages,
                       s.status AS shop_status,
                       EXISTS (SELECT 1 FROM shop_memberships owner
                               JOIN users owner_account ON owner_account.id = owner.user_id
                               WHERE owner.shop_id = s.id AND owner.role = 'OWNER'
                                 AND owner.status = 'ACTIVE' AND owner.revoked_at IS NULL
                                 AND owner_account.status = 'ACTIVE' AND owner_account.deleted_at IS NULL
                                 AND owner_account.email_verified = TRUE) AS owner_available,
                       latest.resolved_at,
                       COALESCE(latest.last_activity_at, c.last_opened_at) AS sort_at
                FROM customer_shop_chat_contacts c
                JOIN shops s ON s.id = c.shop_id
                LEFT JOIN managed_media_objects logo ON logo.id = s.logo_media_id AND logo.shop_id = s.id
                     AND logo.purpose = 'SHOP_LOGO' AND logo.status = 'READY' AND logo.delivery_type = 'upload'
                LEFT JOIN LATERAL (
                    SELECT t.id, t.status, t.last_activity_at, t.resolved_at
                    FROM support_tickets t
                    WHERE t.ticket_kind = 'SHOP' AND t.customer_id = c.customer_id AND t.shop_id = c.shop_id
                    ORDER BY t.last_activity_at DESC, t.id DESC LIMIT 1
                ) latest ON TRUE
                LEFT JOIN LATERAL (
                    SELECT m.body
                    FROM support_tickets message_ticket
                    JOIN support_messages m ON m.ticket_id = message_ticket.id
                    WHERE message_ticket.ticket_kind = 'SHOP' AND message_ticket.customer_id = c.customer_id
                      AND message_ticket.shop_id = c.shop_id AND m.shop_id = c.shop_id
                      AND m.visibility = 'CUSTOMER'
                    ORDER BY m.created_at DESC, m.id DESC LIMIT 1
                ) latest_message ON TRUE
                WHERE c.customer_id = ?
                """;
    }

    private String platformInboxSelect() {
        return """
                SELECT 'PLATFORM_SUPPORT'::text AS scope, NULL::uuid AS contact_id,
                       NULL::uuid AS shop_id, NULL::text AS shop_slug, t.id AS conversation_id,
                       t.subject AS title, NULL::text AS avatar_url, latest_message.body AS preview,
                       t.last_activity_at,
                       CASE WHEN EXISTS (SELECT 1 FROM support_messages unread_message
                           WHERE unread_message.ticket_id = t.id AND unread_message.visibility = 'CUSTOMER'
                             AND unread_message.author_id IS NOT NULL AND unread_message.author_id <> t.customer_id
                             AND unread_message.created_at > COALESCE((SELECT r.last_read_at
                                 FROM support_ticket_reads r WHERE r.ticket_id = t.id AND r.user_id = t.customer_id),
                                 TIMESTAMPTZ 'epoch')) THEN 1 ELSE 0 END::bigint AS unread_conversations,
                       t.status, EXISTS (SELECT 1 FROM support_messages any_message
                           WHERE any_message.ticket_id = t.id AND any_message.visibility = 'CUSTOMER') AS has_messages,
                       NULL::text AS shop_status, FALSE AS owner_available, t.resolved_at,
                       t.last_activity_at AS sort_at
                FROM support_tickets t
                LEFT JOIN LATERAL (
                    SELECT m.body FROM support_messages m
                    WHERE m.ticket_id = t.id AND m.visibility = 'CUSTOMER'
                    ORDER BY m.created_at DESC, m.id DESC LIMIT 1
                ) latest_message ON TRUE
                WHERE t.ticket_kind = 'PLATFORM_SUPPORT' AND t.customer_id = ?
                """;
    }

    private long countInboxRows(InboxScope scope, UUID customerId) {
        String shopCount = scope == InboxScope.PLATFORM_SUPPORT ? "0" :
                "(SELECT COUNT(*) FROM customer_shop_chat_contacts WHERE customer_id = ?)";
        String supportCount = scope == InboxScope.SHOP ? "0" :
                "(SELECT COUNT(*) FROM support_tickets WHERE ticket_kind = 'PLATFORM_SUPPORT' AND customer_id = ?)";
        List<Object> args = new ArrayList<>();
        if (scope != InboxScope.PLATFORM_SUPPORT) args.add(customerId);
        if (scope != InboxScope.SHOP) args.add(customerId);
        Long count = jdbc.queryForObject("SELECT " + shopCount + " + " + supportCount, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private LatestConversation latestShopConversation(UUID customerId, UUID shopId) {
        return jdbc.query("""
                SELECT latest.id AS conversation_id,
                       EXISTS (SELECT 1 FROM support_tickets t JOIN support_messages m ON m.ticket_id = t.id
                               WHERE t.ticket_kind = 'SHOP' AND t.customer_id = ? AND t.shop_id = ?
                                 AND m.shop_id = t.shop_id AND m.visibility = 'CUSTOMER') AS has_messages
                FROM support_tickets latest
                WHERE latest.ticket_kind = 'SHOP' AND latest.customer_id = ? AND latest.shop_id = ?
                ORDER BY latest.last_activity_at DESC, latest.id DESC LIMIT 1
                """, rs -> rs.next() ? new LatestConversation(rs.getObject("conversation_id", UUID.class),
                        rs.getBoolean("has_messages")) : null, customerId, shopId, customerId, shopId);
    }

    private boolean hasActiveOwner(UUID shopId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM shop_memberships owner
                               JOIN users owner_account ON owner_account.id = owner.user_id
                               WHERE owner.shop_id = ? AND owner.role = 'OWNER' AND owner.status = 'ACTIVE'
                                 AND owner.revoked_at IS NULL AND owner_account.status = 'ACTIVE'
                                 AND owner_account.deleted_at IS NULL AND owner_account.email_verified = TRUE)
                """, Boolean.class, shopId);
        return Boolean.TRUE.equals(result);
    }

    private boolean canSend(String scope, String shopStatus, boolean ownerAvailable, String ticketStatus,
                            Instant resolvedAt) {
        if ("SHOP".equals(scope)) {
            return shopChatEnabled() && ShopStatus.ACTIVE.name().equals(shopStatus) && ownerAvailable;
        }
        if (ticketStatus == null || SupportTicketStatus.CLOSED.name().equals(ticketStatus)) return false;
        return !SupportTicketStatus.RESOLVED.name().equals(ticketStatus)
                || resolvedAt != null && !resolvedAt.isBefore(Instant.now().minus(SUPPORT_REPLY_WINDOW));
    }

    private String publicShopLogo(UUID shopId) {
        String raw = jdbc.query("""
                SELECT secure_url FROM managed_media_objects
                WHERE shop_id = ? AND purpose = 'SHOP_LOGO' AND status = 'READY' AND delivery_type = 'upload'
                  AND id = (SELECT logo_media_id FROM shops WHERE id = ?)
                """, rs -> rs.next() ? rs.getString(1) : null, shopId, shopId);
        return publicShopLogo(raw);
    }

    private String publicShopLogo(String raw) {
        if (raw == null || raw.length() > 2048) return null;
        try {
            URI uri = URI.create(raw);
            String cloudName = cloudinary == null ? null : cloudinary.getCloudName();
            String path = uri.getRawPath();
            if ("https".equalsIgnoreCase(uri.getScheme()) && "res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && cloudName != null
                    && path != null && path.startsWith("/" + cloudName + "/image/upload/")) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // Omit malformed historical URLs.
        }
        return null;
    }

    private ShopEntity requireShop(UUID shopId, boolean activeOnly) {
        ShopEntity shop = shopId == null ? null : shops.findById(shopId).orElse(null);
        if (shop == null || shop.getKind() != ShopKind.VENDOR
                || activeOnly && shop.getStatus() != ShopStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return shop;
    }

    private UserEntity requireCustomer(UserPrincipal actor) {
        if (actor == null || actor.getId() == null || !actor.isEnabled()
                || (actor.getRole() != AppRole.USER && actor.getRole() != AppRole.CUSTOMER)) {
            throw new AccessDeniedException("Đăng nhập bằng tài khoản khách để tiếp tục.");
        }
        UserEntity user = users.findById(actor.getId())
                .orElseThrow(() -> new AccessDeniedException("Đăng nhập bằng tài khoản khách để tiếp tục."));
        if (user.getStatus() != UserStatus.ACTIVE
                || (user.getRole() != UserRole.USER && user.getRole() != UserRole.CUSTOMER)) {
            throw new AccessDeniedException("Chỉ tài khoản khách đang hoạt động mới được dùng hộp thư.");
        }
        return user;
    }

    private void requireShopChatEnabled() {
        if (!shopChatEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private boolean shopChatEnabled() {
        return features != null && features.isEnabled(Feature.SHOP_CHAT)
                && features.isEnabled(Feature.VENDOR_MANAGEMENT);
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static int clampPage(int page) { return Math.min(Math.max(page, 0), MAX_PAGE); }

    private record LatestConversation(UUID conversationId, boolean hasMessages) { }
    public enum InboxScope { ALL, SHOP, PLATFORM_SUPPORT }
    public record OpenContactResponse(UUID contactId, UUID shopId, String shopSlug, String shopName,
            String shopLogoUrl, UUID latestConversationId, boolean hasMessages, Instant lastOpenedAt,
            boolean canSend, String shopStatus) { }
    public record InboxPage(List<InboxItem> items, int page, boolean hasMore, long total) { }
    public record InboxItem(String scope, UUID contactId, UUID shopId, String shopSlug,
            UUID conversationId, String title, String avatarUrl, String preview, Instant lastActivityAt,
            long unreadConversations, String status, boolean hasMessages, boolean canSend, String shopStatus) { }
    public record UnreadSummary(long total, long shop, long platformSupport) { }
}
