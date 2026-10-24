package com.utewedding.app.service.impl;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PrivacyLegalBasis;
import com.utewedding.app.domain.PrivacyRequestStatus;
import com.utewedding.app.domain.PrivacyRequestType;
import com.utewedding.app.domain.SupportTicketKind;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.CustomerShopChatContactEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.PrivacyConsentRecordEntity;
import com.utewedding.app.domain.entity.PrivacyRequestEntity;
import com.utewedding.app.domain.entity.SupportMessageAttachmentEntity;
import com.utewedding.app.domain.entity.SupportMessageEntity;
import com.utewedding.app.domain.entity.SupportTicketEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.AuditLogRepository;
import com.utewedding.app.repository.AuthSessionRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.CustomerShopChatContactRepository;
import com.utewedding.app.repository.GuestRepository;
import com.utewedding.app.repository.ManagedMediaObjectRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.PrivacyConsentRecordRepository;
import com.utewedding.app.repository.PrivacyRequestRepository;
import com.utewedding.app.repository.PrivacyRetentionRuleRepository;
import com.utewedding.app.repository.ProductFavoriteRepository;
import com.utewedding.app.repository.ProductReviewRepository;
import com.utewedding.app.repository.RsvpRepository;
import com.utewedding.app.repository.ShipInfoRepository;
import com.utewedding.app.repository.ShopSupportAttachmentRepository;
import com.utewedding.app.repository.ShopSupportMessageRepository;
import com.utewedding.app.repository.ShopSupportTicketRepository;
import com.utewedding.app.repository.UserNotificationRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.repository.WeddingCeremonyRepository;
import com.utewedding.app.repository.WeddingDraftRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.repository.WishRepository;
import com.utewedding.app.security.CustomUserDetailsService;
import com.utewedding.app.security.WebSessionRevocationService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.SessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivacyService {
    public static final String NOTICE_VERSION = "2026-09-29";
    public static final String MARKETING_PURPOSE = "MARKETING_EMAIL";
    private static final Set<PrivacyRequestStatus> OPEN_STATUSES =
            Set.of(PrivacyRequestStatus.PENDING, PrivacyRequestStatus.IN_PROGRESS);
    private static final Set<FulfillmentStatus> TERMINAL_FULFILLMENTS =
            Set.of(FulfillmentStatus.DELIVERED, FulfillmentStatus.RETURNED, FulfillmentStatus.CANCELLED);

    private final UserRepository users;
    private final ShipInfoRepository addresses;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentRepository payments;
    private final OrderFulfillmentRepository fulfillments;
    private final WeddingRepository weddings;
    private final WeddingDraftRepository weddingDrafts;
    private final WeddingCeremonyRepository ceremonies;
    private final GuestRepository guests;
    private final RsvpRepository rsvps;
    private final WishRepository wishes;
    private final ProductReviewRepository reviews;
    private final ProductFavoriteRepository favorites;
    private final UserNotificationRepository notifications;
    private final AuthSessionRepository authSessions;
    private final CartRepository carts;
    private final PrivacyRequestRepository requests;
    private final PrivacyConsentRecordRepository consents;
    private final PrivacyRetentionRuleRepository retention;
    private final AuditLogRepository auditLogs;
    private final AuditLogPort audit;
    private final SessionService sessions;
    private final WebSessionRevocationService webSessions;
    private final CustomUserDetailsService userDetails;
    private final PasswordEncoder passwordEncoder;
    private final TextSanitizer sanitizer;
    private final PrivilegeGuard privilegeGuard = new PrivilegeGuard();
    private ManagedMediaObjectRepository managedMediaObjects;
    private ManagedMediaService managedMedia;
    private com.utewedding.app.repository.ProductViewHistoryRepository recentlyViewedHistory;
    private CustomerShopChatContactRepository shopChatContacts;
    private ShopSupportTicketRepository shopChatTickets;
    private ShopSupportMessageRepository shopChatMessages;
    private ShopSupportAttachmentRepository shopChatAttachments;

    public PrivacyService(UserRepository users,
                          ShipInfoRepository addresses, OrderRepository orders,
                          OrderItemRepository orderItems, PaymentRepository payments,
                          OrderFulfillmentRepository fulfillments, WeddingRepository weddings,
                          WeddingDraftRepository weddingDrafts, WeddingCeremonyRepository ceremonies,
                          GuestRepository guests, RsvpRepository rsvps, WishRepository wishes,
                          ProductReviewRepository reviews, ProductFavoriteRepository favorites,
                          UserNotificationRepository notifications, AuthSessionRepository authSessions,
                          CartRepository carts, PrivacyRequestRepository requests,
                          PrivacyConsentRecordRepository consents, PrivacyRetentionRuleRepository retention,
                          AuditLogRepository auditLogs, AuditLogPort audit, SessionService sessions,
                          WebSessionRevocationService webSessions, CustomUserDetailsService userDetails,
                          PasswordEncoder passwordEncoder, TextSanitizer sanitizer) {
        this.users = users;
        this.addresses = addresses;
        this.orders = orders;
        this.orderItems = orderItems;
        this.payments = payments;
        this.fulfillments = fulfillments;
        this.weddings = weddings;
        this.weddingDrafts = weddingDrafts;
        this.ceremonies = ceremonies;
        this.guests = guests;
        this.rsvps = rsvps;
        this.wishes = wishes;
        this.reviews = reviews;
        this.favorites = favorites;
        this.notifications = notifications;
        this.authSessions = authSessions;
        this.carts = carts;
        this.requests = requests;
        this.consents = consents;
        this.retention = retention;
        this.auditLogs = auditLogs;
        this.audit = audit;
        this.sessions = sessions;
        this.webSessions = webSessions;
        this.userDetails = userDetails;
        this.passwordEncoder = passwordEncoder;
        this.sanitizer = sanitizer;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachManagedMedia(ManagedMediaObjectRepository objects, ManagedMediaService service) {
        this.managedMediaObjects = objects;
        this.managedMedia = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachRecentlyViewedHistory(com.utewedding.app.repository.ProductViewHistoryRepository history) {
        this.recentlyViewedHistory = history;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachShopChatPrivacyRepositories(CustomerShopChatContactRepository contacts,
                                           ShopSupportTicketRepository tickets,
                                           ShopSupportMessageRepository messages,
                                           ShopSupportAttachmentRepository attachments) {
        this.shopChatContacts = contacts;
        this.shopChatTickets = tickets;
        this.shopChatMessages = messages;
        this.shopChatAttachments = attachments;
    }

    @Transactional(readOnly = true)
    public List<com.utewedding.app.domain.entity.PrivacyRetentionRuleEntity> retentionRules() {
        return retention.findByActiveTrueOrderByCategoryKeyAsc();
    }

    public List<DataInventoryItem> dataInventory() {
        return List.of(
                new DataInventoryItem("ACCOUNT_PROFILE", "Email, tên hiển thị, trạng thái tài khoản",
                        "Vận hành tài khoản và xác thực", PrivacyLegalBasis.CONTRACT),
                new DataInventoryItem("SAVED_ADDRESS", "Người nhận, điện thoại và địa chỉ giao hàng",
                        "Thực hiện hợp đồng và giao hàng", PrivacyLegalBasis.CONTRACT),
                new DataInventoryItem("ORDER_TRANSACTION", "Đơn hàng, thanh toán và lịch sử fulfillment",
                        "Thực hiện hợp đồng, kế toán và giải quyết sự cố", PrivacyLegalBasis.LEGAL_OBLIGATION),
                new DataInventoryItem("WEDDING_CONTENT", "Nội dung thiệp, khách mời, RSVP, lời chúc và media",
                        "Cung cấp dịch vụ thiệp cưới", PrivacyLegalBasis.CONTRACT),
                new DataInventoryItem("SHOP_CHAT", "Liên hệ cửa hàng và lịch sử tin nhắn SHOP hiển thị cho khách",
                        "Trao đổi với cửa hàng", PrivacyLegalBasis.CONTRACT),
                new DataInventoryItem("SECURITY_AUDIT", "Phiên đăng nhập, IP rút gọn/băm, user-agent và audit",
                        "An toàn hệ thống và chống gian lận", PrivacyLegalBasis.LEGITIMATE_INTEREST),
                new DataInventoryItem("MARKETING_EMAIL", "Lựa chọn nhận email marketing",
                        "Gửi truyền thông tự nguyện", PrivacyLegalBasis.CONSENT));
    }

    @Transactional(readOnly = true)
    public List<PrivacyRequestEntity> requestsForUser(UUID userId) {
        return requests.findByUserIdOrderByRequestedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Page<PrivacyRequestEntity> listRequests(PrivacyRequestStatus status, Pageable pageable) {
        return status == null ? requests.findAllByOrderByRequestedAtDesc(pageable)
                : requests.findByStatusOrderByRequestedAtAsc(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<PrivacyRequestEntity> listRequests(PrivacyRequestType type, PrivacyRequestStatus status,
                                                  Pageable pageable) {
        return requests.searchAdmin(type, status, pageable);
    }

    @Transactional(readOnly = true)
    public Map<UUID, PrivacyOwnerView> ownerViews(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        Map<UUID, PrivacyOwnerView> result = new LinkedHashMap<>();
        users.findAllById(userIds).forEach(user -> result.put(user.getId(),
                new PrivacyOwnerView(user.getDisplayName(), user.getEmail())));
        return Map.copyOf(result);
    }

    public record PrivacyOwnerView(String displayName, String email) { }

    @Transactional(readOnly = true)
    public boolean marketingConsent(UUID userId) {
        return consents.findFirstByUserIdAndPurposeOrderByRecordedAtDescIdDesc(userId, MARKETING_PURPOSE)
                .map(PrivacyConsentRecordEntity::isGranted).orElse(false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordMarketingConsent(UUID userId, boolean granted, RequestContext context) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        var latest = consents.findFirstByUserIdAndPurposeOrderByRecordedAtDescIdDesc(userId, MARKETING_PURPOSE);
        if (latest.isPresent() && latest.get().isGranted() == granted
                && NOTICE_VERSION.equals(latest.get().getNoticeVersion())) return;
        PrivacyConsentRecordEntity record = new PrivacyConsentRecordEntity();
        record.setUserId(userId);
        record.setPurpose(MARKETING_PURPOSE);
        record.setGranted(granted);
        record.setLegalBasis(PrivacyLegalBasis.CONSENT);
        record.setNoticeVersion(NOTICE_VERSION);
        record.setSource("PROFILE");
        consents.save(record);
        audit.record("PRIVACY_CONSENT_RECORDED", userId, "PrivacyConsent", record.getId(),
                Map.of("purpose", MARKETING_PURPOSE, "granted", granted,
                        "noticeVersion", NOTICE_VERSION), context);
    }

    @Transactional(rollbackFor = Exception.class)
    public PrivacyRequestEntity requestDeletion(UUID userId, String currentPassword,
                                                String reason, RequestContext context) {
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        if (currentPassword == null || currentPassword.isBlank()
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessConflictException("Mật khẩu hiện tại không chính xác.");
        }
        var open = requests.findFirstByUserIdAndRequestTypeAndStatusInOrderByRequestedAtDesc(
                userId, PrivacyRequestType.DELETION, OPEN_STATUSES);
        if (open.isPresent()) {
            throw new BusinessConflictException("Đã có yêu cầu xóa tài khoản đang chờ quản trị viên duyệt.");
        }
        PrivacyRequestEntity request = new PrivacyRequestEntity();
        request.setUserId(userId);
        request.setRequestType(PrivacyRequestType.DELETION);
        request.setRequestReason(sanitizer.trimToNull(reason, 2000));
        request = requests.saveAndFlush(request);
        audit.record("PRIVACY_DELETION_REQUESTED", userId, "PrivacyRequest", request.getId(),
                Map.of("type", PrivacyRequestType.DELETION.name()), context);
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> exportData(UUID userId, RequestContext context) {
        var user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        PrivacyRequestEntity request = new PrivacyRequestEntity();
        request.setUserId(userId);
        request.setRequestType(PrivacyRequestType.EXPORT);
        request.setStatus(PrivacyRequestStatus.COMPLETED);
        request.setProcessedAt(Instant.now());
        request = requests.saveAndFlush(request);
        audit.record("PRIVACY_EXPORT_COMPLETED", userId, "PrivacyRequest", request.getId(),
                Map.of("noticeVersion", NOTICE_VERSION), context);

        List<OrderEntity> userOrders = orders.findByUserIdOrderByCreatedAtDesc(userId);
        List<UUID> orderIds = userOrders.stream().map(OrderEntity::getId).toList();
        var userWeddings = weddings.findByOwnerIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> weddingData = new ArrayList<>();
        for (var wedding : userWeddings) {
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("wedding", weddingData(wedding));
            bundle.put("draft", weddingDrafts.findByWeddingId(wedding.getId())
                    .map(this::weddingDraftData).orElse(null));
            bundle.put("ceremonies", ceremonies.findByWeddingIdOrderByDisplayOrderAsc(wedding.getId())
                    .stream().map(this::ceremonyData).toList());
            bundle.put("guests", guests.findByWeddingIdOrderByCreatedAtAsc(wedding.getId())
                    .stream().map(this::guestData).toList());
            bundle.put("rsvps", rsvps.findByWeddingId(wedding.getId())
                    .stream().map(this::rsvpData).toList());
            bundle.put("wishes", wishes.findByWeddingIdOrderByCreatedAtDesc(wedding.getId())
                    .stream().map(this::wishData).toList());
            weddingData.add(bundle);
        }

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", Instant.now());
        export.put("privacyNoticeVersion", NOTICE_VERSION);
        export.put("account", accountData(user));
        export.put("savedAddresses", addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId)
                .stream().map(this::addressData).toList());
        export.put("orders", userOrders.stream().map(this::orderData).toList());
        export.put("orderItems", orderIds.isEmpty() ? List.of()
                : orderItems.findByOrderIdInOrderByOrderIdAscIdAsc(orderIds)
                        .stream().map(this::orderItemData).toList());
        export.put("payments", orderIds.isEmpty() ? List.of()
                : payments.findByOrderIdInOrderByOrderIdAscCreatedAtDesc(orderIds)
                        .stream().map(this::paymentData).toList());
        export.put("weddings", weddingData);
        export.put("reviews", reviews.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::reviewData).toList());
        export.put("favorites", favorites.findByUserIdOrderByCreatedAtDesc(
                userId, Pageable.unpaged()).getContent().stream().map(this::favoriteData).toList());
        export.put("recentlyViewed", recentlyViewedHistory == null ? List.of()
                : recentlyViewedHistory.findRecentForExport(userId, Instant.now()
                        .minus(java.time.Duration.ofDays(90))).stream()
                        .map(item -> fields("productId", item.getProductId(),
                                "lastViewedAt", item.getLastViewedAt())).toList());
        export.put("notifications", notifications.findAllByRecipientIdOrderByCreatedAtDesc(userId)
                .stream().map(this::notificationData).toList());
        export.put("shopChatContacts", shopChatData(userId));
        export.put("activeSessions", authSessions.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId)
                .stream().map(this::sessionData).toList());
        export.put("consentHistory", consents.findByUserIdOrderByRecordedAtDescIdDesc(userId)
                .stream().map(this::consentData).toList());
        export.put("privacyRequests", requests.findByUserIdOrderByRequestedAtDesc(userId)
                .stream().map(this::privacyRequestData).toList());
        export.put("auditTrail", auditLogs.findAllByActorIdOrderByCreatedAtDesc(userId)
                .stream().map(this::auditData).toList());
        return export;
    }

    /**
     * Returns only the customer's explicitly saved shop contacts and the
     * customer-visible history for those contacts. Platform support tickets,
     * INTERNAL messages, and rows belonging to another customer or shop are
     * intentionally excluded from a personal export.
     */
    private List<Map<String, Object>> shopChatData(UUID customerId) {
        if (shopChatContacts == null || shopChatTickets == null || shopChatMessages == null) {
            return List.of();
        }

        List<CustomerShopChatContactEntity> contacts =
                shopChatContacts.findByCustomerIdOrderByLastOpenedAtDescIdAsc(customerId);
        if (contacts == null || contacts.isEmpty()) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (CustomerShopChatContactEntity contact : contacts) {
            if (contact == null || !customerId.equals(contact.getCustomerId())
                    || contact.getShopId() == null) {
                continue;
            }

            Page<SupportTicketEntity> ticketPage = shopChatTickets
                    .findByTicketKindAndShopIdAndCustomerIdOrderByLastActivityAtDesc(
                            SupportTicketKind.SHOP, contact.getShopId(), customerId, Pageable.unpaged());
            List<SupportTicketEntity> tickets = ticketPage == null || ticketPage.getContent() == null
                    ? List.of() : ticketPage.getContent();

            List<Map<String, Object>> conversations = new ArrayList<>();
            for (SupportTicketEntity ticket : tickets) {
                if (ticket == null || ticket.getTicketKind() != SupportTicketKind.SHOP
                        || !customerId.equals(ticket.getCustomerId())
                        || !contact.getShopId().equals(ticket.getShopId())) {
                    continue;
                }
                conversations.add(shopChatConversationData(ticket, customerId));
            }
            Set<UUID> conversationIds = conversations.stream()
                    .map(row -> (UUID) row.get("id"))
                    .collect(java.util.stream.Collectors.toSet());
            UUID safeLastTicketId = conversationIds.contains(contact.getLastTicketId())
                    ? contact.getLastTicketId() : null;
            result.add(fields("scope", "SHOP", "contactId", contact.getId(), "shopId", contact.getShopId(),
                    "firstOpenedAt", contact.getFirstOpenedAt(), "lastOpenedAt", contact.getLastOpenedAt(),
                    "lastTicketId", safeLastTicketId, "conversations", conversations));
        }
        return result;
    }

    private Map<String, Object> shopChatConversationData(SupportTicketEntity ticket, UUID customerId) {
        List<SupportMessageEntity> messages = shopChatMessages
                .findByTicketIdAndShopIdAndVisibilityOrderByCreatedAtAscIdAsc(
                        ticket.getId(), ticket.getShopId(), "CUSTOMER");
        if (messages == null) messages = List.of();

        List<SupportMessageEntity> visibleRows = messages.stream()
                .filter(message -> message != null
                        && "CUSTOMER".equals(message.getVisibility())
                        && ticket.getId().equals(message.getTicketId())
                        && ticket.getShopId().equals(message.getShopId())
                        && message.getId() != null)
                .toList();
        List<UUID> messageIds = visibleRows.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<UUID>> attachmentsByMessage = shopChatAttachmentIds(ticket.getShopId(), messageIds);

        List<Map<String, Object>> visibleMessages = visibleRows.stream()
                .map(message -> fields("id", message.getId(), "body", message.getBody(),
                        "createdAt", message.getCreatedAt(),
                        "sentByCustomer", customerId.equals(message.getAuthorId()),
                        "attachments", attachmentsByMessage.getOrDefault(message.getId(), List.of()).stream()
                                .map(mediaId -> fields("managedMediaId", mediaId)).toList()))
                .toList();
        Map<String, Object> latestMessage = visibleMessages.isEmpty()
                ? null : visibleMessages.get(visibleMessages.size() - 1);
        return fields("kind", "SHOP", "id", ticket.getId(), "ticketNumber", ticket.getTicketNumber(),
                "subject", ticket.getSubject(), "category", ticket.getCategory(),
                "priority", ticket.getPriority(), "status", ticket.getStatus(),
                "createdAt", ticket.getCreatedAt(), "lastActivityAt", ticket.getLastActivityAt(),
                "messageCount", visibleMessages.size(), "latestMessage", latestMessage,
                "messages", visibleMessages);
    }

    /** Exports attachment identifiers only, and only for the ticket's customer-visible messages. */
    private Map<UUID, List<UUID>> shopChatAttachmentIds(UUID shopId, List<UUID> messageIds) {
        if (shopChatAttachments == null || shopId == null || messageIds.isEmpty()) return Map.of();

        List<SupportMessageAttachmentEntity> attachments =
                shopChatAttachments.findByShopIdAndMessageIds(shopId, messageIds);
        if (attachments == null || attachments.isEmpty()) return Map.of();

        Set<UUID> eligibleMessageIds = Set.copyOf(messageIds);
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        for (SupportMessageAttachmentEntity attachment : attachments) {
            if (attachment == null || !shopId.equals(attachment.getShopId()) || attachment.getId() == null) continue;
            UUID messageId = attachment.getId().getMessageId();
            UUID mediaId = attachment.getId().getManagedMediaId();
            if (messageId == null || mediaId == null || !eligibleMessageIds.contains(messageId)) continue;
            result.computeIfAbsent(messageId, ignored -> new ArrayList<>()).add(mediaId);
        }
        result.replaceAll((messageId, mediaIds) -> mediaIds.stream().sorted().toList());
        return result;
    }

    private Map<String, Object> addressData(com.utewedding.app.domain.entity.ShipInfoEntity address) {
        return fields("id", address.getId(), "label", address.getLabel(),
                "recipientName", address.getRecipientName(), "phone", address.getPhone(),
                "addressLine", address.getAddressLine(), "ward", address.getWard(),
                "district", address.getDistrict(), "province", address.getProvince(),
                "provinceCode", address.getProvinceCode(), "provinceName", address.getProvinceName(),
                "wardCode", address.getWardCode(), "wardName", address.getWardName(),
                "postalCode", address.getPostalCode(), "note", address.getNote(),
                "defaultAddress", address.isDefaultAddress(), "createdAt", address.getCreatedAt(),
                "updatedAt", address.getUpdatedAt());
    }

    private Map<String, Object> orderData(OrderEntity order) {
        return fields("id", order.getId(), "orderNumber", order.getOrderNumber(),
                "status", order.getStatus(), "subtotal", order.getSubtotal(),
                "discountAmount", order.getDiscountAmount(), "totalAmount", order.getTotalAmount(),
                "currency", order.getCurrency(), "discountCode", order.getDiscountCode(),
                "voucherType", order.getVoucherType(), "voucherDiscountAmount", order.getVoucherDiscountAmount(),
                "shippingFeeBeforeDiscount", order.getShippingFeeBeforeDiscount(),
                "shippingDiscountAmount", order.getShippingDiscountAmount(),
                "paymentMethod", order.getPaymentMethod(), "paymentStatus", order.getPaymentStatus(),
                "shippingRecipientName", order.getShippingRecipientName(), "shippingPhone", order.getShippingPhone(),
                "shippingAddress", order.getShippingAddress(), "shippingWard", order.getShippingWard(),
                "shippingDistrict", order.getShippingDistrict(), "shippingProvince", order.getShippingProvince(),
                "shippingProvinceCode", order.getShippingProvinceCode(),
                "shippingProvinceName", order.getShippingProvinceName(),
                "shippingWardCode", order.getShippingWardCode(), "shippingWardName", order.getShippingWardName(),
                "shippingFee", order.getShippingFee(), "trackingNumber", order.getTrackingNumber(),
                "paidAt", order.getPaidAt(), "cancelledAt", order.getCancelledAt(),
                "cancelReason", order.getCancelReason(), "deliveredAt", order.getDeliveredAt(),
                "returnedAt", order.getReturnedAt(), "expiresAt", order.getExpiresAt(),
                "createdAt", order.getCreatedAt(), "updatedAt", order.getUpdatedAt());
    }

    private Map<String, Object> orderItemData(com.utewedding.app.domain.entity.OrderItemEntity item) {
        return fields("id", item.getId(), "orderId", item.getOrderId(), "productId", item.getProductId(),
                "productName", item.getProductName(), "unitPrice", item.getUnitPrice(),
                "quantity", item.getQuantity(), "discountAmount", item.getDiscountAmount(),
                "lineTotal", item.getLineTotal(), "voucherDiscountAllocated", item.getVoucherDiscountAllocated(),
                "netPaidAmount", item.getNetPaidAmount());
    }

    private Map<String, Object> paymentData(com.utewedding.app.domain.entity.PaymentEntity payment) {
        return fields("id", payment.getId(), "orderId", payment.getOrderId(), "status", payment.getStatus(),
                "amount", payment.getAmount(), "currency", payment.getCurrency(), "provider", payment.getProvider(),
                "refundedAmount", payment.getRefundedAmount(), "collectedAmount", payment.getCollectedAmount(),
                "finalDueAmount", payment.getFinalDueAmount(), "createdAt", payment.getCreatedAt(),
                "processedAt", payment.getProcessedAt());
    }

    private Map<String, Object> reviewData(com.utewedding.app.domain.entity.ProductReviewEntity review) {
        return fields("id", review.getId(), "productId", review.getProductId(), "orderId", review.getOrderId(),
                "rating", review.getRating(), "content", review.getContent(), "status", review.getStatus(),
                "createdAt", review.getCreatedAt(), "updatedAt", review.getUpdatedAt());
    }

    private Map<String, Object> favoriteData(com.utewedding.app.domain.entity.ProductFavoriteEntity favorite) {
        return fields("productId", favorite.getProductId(), "createdAt", favorite.getCreatedAt());
    }

    private Map<String, Object> notificationData(com.utewedding.app.domain.entity.UserNotificationEntity notification) {
        return fields("type", notification.getType(), "title", notification.getTitle(),
                "body", notification.getBody(), "readAt", notification.getReadAt(),
                "createdAt", notification.getCreatedAt());
    }

    private Map<String, Object> consentData(com.utewedding.app.domain.entity.PrivacyConsentRecordEntity consent) {
        return fields("purpose", consent.getPurpose(), "granted", consent.isGranted(),
                "legalBasis", consent.getLegalBasis(), "noticeVersion", consent.getNoticeVersion(),
                "source", consent.getSource(), "recordedAt", consent.getRecordedAt());
    }

    private Map<String, Object> privacyRequestData(com.utewedding.app.domain.entity.PrivacyRequestEntity request) {
        return fields("requestType", request.getRequestType(), "status", request.getStatus(),
                "requestReason", request.getRequestReason(), "requestedAt", request.getRequestedAt(),
                "processedAt", request.getProcessedAt());
    }

    private Map<String, Object> weddingData(com.utewedding.app.domain.entity.WeddingEntity wedding) {
        return fields("id", wedding.getId(), "eventType", wedding.getEventType(), "status", wedding.getStatus(),
                "slug", wedding.getSlug(), "internalTitle", wedding.getInternalTitle(), "version", wedding.getVersion(),
                "publishedAt", wedding.getPublishedAt(), "createdAt", wedding.getCreatedAt(),
                "updatedAt", wedding.getUpdatedAt());
    }

    private Map<String, Object> weddingDraftData(com.utewedding.app.domain.entity.WeddingDraftEntity draft) {
        return fields("brideName", draft.getBrideName(), "groomName", draft.getGroomName(),
                "brideShortName", draft.getBrideShortName(), "groomShortName", draft.getGroomShortName(),
                "brideIntro", draft.getBrideIntro(), "groomIntro", draft.getGroomIntro(),
                "brideFamilyAddress", draft.getBrideFamilyAddress(), "groomFamilyAddress", draft.getGroomFamilyAddress(),
                "familyNote", draft.getFamilyNote(), "headline", draft.getHeadline(),
                "invitationMessage", draft.getInvitationMessage(), "thankYouMessage", draft.getThankYouMessage(),
                "footerMessage", draft.getFooterMessage());
    }

    private Map<String, Object> ceremonyData(com.utewedding.app.domain.entity.WeddingCeremonyEntity ceremony) {
        return fields("id", ceremony.getId(), "type", ceremony.getType(), "title", ceremony.getTitle(),
                "startDateTime", ceremony.getStartDateTime(), "endDateTime", ceremony.getEndDateTime(),
                "timezone", ceremony.getTimezone(), "venueName", ceremony.getVenueName(),
                "venueAddress", ceremony.getVenueAddress(), "googleMapsUrl", ceremony.getGoogleMapsUrl(),
                "description", ceremony.getDescription(), "displayOrder", ceremony.getDisplayOrder(),
                "visible", ceremony.isVisible());
    }

    private Map<String, Object> guestData(com.utewedding.app.domain.entity.GuestEntity guest) {
        return fields("id", guest.getId(), "weddingId", guest.getWeddingId(), "name", guest.getName(),
                "groupName", guest.getGroupName(), "maxAttendees", guest.getMaxAttendees(),
                "phone", guest.getPhone(), "email", guest.getEmail(), "salutation", guest.getSalutation(),
                "invitationMessageOverride", guest.getInvitationMessageOverride(), "sentAt", guest.getSentAt(),
                "firstOpenedAt", guest.getFirstOpenedAt(), "lastOpenedAt", guest.getLastOpenedAt(),
                "openCount", guest.getOpenCount(), "createdAt", guest.getCreatedAt(), "updatedAt", guest.getUpdatedAt());
    }

    private Map<String, Object> rsvpData(com.utewedding.app.domain.entity.RsvpEntity rsvp) {
        return fields("weddingId", rsvp.getWeddingId(), "guestId", rsvp.getGuestId(),
                "attendanceStatus", rsvp.getAttendanceStatus(), "attendeeCount", rsvp.getAttendeeCount(),
                "inviteeCount", rsvp.getInviteeCount(), "guestName", rsvp.getGuestName(),
                "phone", rsvp.getPhone(), "note", rsvp.getNote(), "dietaryNotes", rsvp.getDietaryNotes(),
                "submittedAt", rsvp.getSubmittedAt(), "createdAt", rsvp.getCreatedAt(), "updatedAt", rsvp.getUpdatedAt());
    }

    private Map<String, Object> wishData(com.utewedding.app.domain.entity.WishEntity wish) {
        return fields("id", wish.getId(), "weddingId", wish.getWeddingId(), "senderName", wish.getSenderName(),
                "message", wish.getMessage(), "status", wish.getStatus(), "submittedAt", wish.getSubmittedAt(),
                "approvedAt", wish.getApprovedAt(), "createdAt", wish.getCreatedAt(), "updatedAt", wish.getUpdatedAt());
    }

    private Map<String, Object> auditData(com.utewedding.app.domain.entity.AuditLogEntity event) {
        return fields("action", event.getAction(), "targetType", event.getTargetType(),
                "targetReference", event.getTargetReference(), "createdAt", event.getCreatedAt());
    }

    private Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public PrivacyRequestEntity processDeletion(UUID requestId, UUID actorId, boolean approve,
                                                 String reason, RequestContext context) {
        PrivacyRequestEntity request = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu privacy."));
        if (request.getRequestType() != PrivacyRequestType.DELETION
                || !OPEN_STATUSES.contains(request.getStatus())) {
            throw new BusinessConflictException("Yêu cầu không còn ở trạng thái có thể xử lý.");
        }
        var actor = users.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản thực hiện thao tác."));
        users.lockActiveSuperAdmins();
        var user = users.findByIdForUpdate(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        privilegeGuard.assertCanMutate(actor, user, false);
        if (user.getRole() == UserRole.SUPER_ADMIN
                && users.countActiveSuperAdminsExcept(user.getId()) == 0) {
            throw new BusinessConflictException("Không thể xóa SUPER_ADMIN đang hoạt động cuối cùng.");
        }
        if (!approve) {
            String decision = required(reason, "Cần nhập lý do từ chối.");
            request.setStatus(PrivacyRequestStatus.REJECTED);
            request.setDecisionReason(decision);
            request.setProcessedBy(actorId);
            request.setProcessedAt(Instant.now());
            requests.save(request);
            audit.record("PRIVACY_REQUEST_REJECTED", actorId, "PrivacyRequest", request.getId(),
                    Map.of("type", request.getRequestType().name()), context);
            return request;
        }

        List<OrderEntity> lockedOrders = orders.findByUserIdForUpdateOrderByIdAsc(user.getId());
        assertNoActiveTransactions(lockedOrders);
        String previousEmail = user.getEmail();
        Instant now = Instant.now();

        for (OrderEntity order : lockedOrders) redactOrder(order);
        orders.saveAllAndFlush(lockedOrders);

        if (managedMediaObjects != null && managedMedia != null) {
            managedMediaObjects.findByOwnerIdAndStatusNot(user.getId(), ManagedMediaStatus.DELETED)
                    .forEach(object -> managedMedia.requestDelete(object.getId()));
        }

        var ownedWeddings = weddings.findByOwnerIdOrderByCreatedAtDesc(user.getId());
        weddings.deleteAll(ownedWeddings);
        addresses.deleteByUserId(user.getId());
        favorites.deleteByUserId(user.getId());
        if (recentlyViewedHistory != null) recentlyViewedHistory.deleteByUserId(user.getId());
        notifications.deleteByRecipientId(user.getId());
        carts.deleteByUserId(user.getId());
        // Contacts are customer-owned presentation data. Remove them after an
        // approved erasure while retaining the SHOP tickets/messages required
        // by the support retention policy.
        if (shopChatContacts != null) shopChatContacts.deleteByCustomerId(user.getId());

        var userReviews = reviews.findAllByUserIdOrderByCreatedAtDesc(user.getId());
        userReviews.forEach(review -> review.setContent("[Nội dung đã được ẩn theo yêu cầu riêng tư]"));
        reviews.saveAll(userReviews);

        var personalAuditRows = auditLogs.findAllByActorIdOrderByCreatedAtDesc(user.getId());
        personalAuditRows.forEach(com.utewedding.app.domain.entity.AuditLogEntity::redactPersonalContext);
        auditLogs.saveAll(personalAuditRows);

        sessions.revokeAllForUser(user.getId(), "PRIVACY_DELETION");
        user.setEmail("deleted+" + user.getId() + "@privacy.invalid");
        user.setDisplayName("Người dùng đã xóa");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.DELETED);
        user.setEmailVerified(false);
        user.setEmailVerifiedAt(null);
        user.setLastLoginAt(null);
        user.setPasswordChangedAt(now);
        user.incrementPasswordVersion();
        user.setDeletedAt(now);
        users.save(user);

        request.setStatus(PrivacyRequestStatus.COMPLETED);
        request.setDecisionReason(sanitizer.trimToNull(reason, 2000));
        request.setProcessedBy(actorId);
        request.setProcessedAt(now);
        requests.save(request);
        audit.record("PRIVACY_DELETION_COMPLETED", actorId, "PrivacyRequest", request.getId(),
                Map.of("accountId", user.getId().toString(), "transactionRecordsPreserved", true), context);
        userDetails.evict(user.getId(), previousEmail);
        webSessions.revokeAllAfterCommit(user.getId(), previousEmail);
        return request;
    }

    private void assertNoActiveTransactions(Collection<OrderEntity> userOrders) {
        for (OrderEntity order : userOrders) {
            if (order.getStatus() == OrderStatus.PENDING) {
                throw new BusinessConflictException("Không thể xóa tài khoản khi còn đơn hàng chờ xử lý.");
            }
            if (order.getStatus() != OrderStatus.PAID) continue;
            var rows = fulfillments.findByOrderIdForUpdateOrderByIdAsc(order.getId());
            if (rows.isEmpty() || rows.stream().anyMatch(row -> !TERMINAL_FULFILLMENTS.contains(row.getStatus()))) {
                throw new BusinessConflictException("Không thể xóa tài khoản khi còn đơn hàng đang giao.");
            }
        }
    }

    private void redactOrder(OrderEntity order) {
        order.setWeddingId(null);
        order.setShipInfoId(null);
        order.setShippingRecipientName(null);
        order.setShippingPhone(null);
        order.setShippingAddress(null);
        order.setShippingWard(null);
        order.setShippingDistrict(null);
        order.setShippingProvince(null);
        order.setShippingProvinceCode(null);
        order.setShippingProvinceName(null);
        order.setShippingWardCode(null);
        order.setShippingWardName(null);
        order.setTrackingNumber(null);
    }

    private Map<String, Object> accountData(com.utewedding.app.domain.entity.UserEntity user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("email", user.getEmail());
        result.put("displayName", user.getDisplayName());
        result.put("role", user.getRole());
        result.put("status", user.getStatus());
        result.put("emailVerified", user.isEmailVerified());
        result.put("emailVerifiedAt", user.getEmailVerifiedAt());
        result.put("lastLoginAt", user.getLastLoginAt());
        result.put("passwordChangedAt", user.getPasswordChangedAt());
        result.put("createdAt", user.getCreatedAt());
        result.put("updatedAt", user.getUpdatedAt());
        return result;
    }

    private Map<String, Object> sessionData(com.utewedding.app.domain.entity.AuthSessionEntity session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.getId());
        result.put("userAgent", session.getUserAgent());
        result.put("ipAddress", session.getIpAddress());
        result.put("expiresAt", session.getExpiresAt());
        result.put("lastUsedAt", session.getLastUsedAt());
        result.put("createdAt", session.getCreatedAt());
        return result;
    }

    private String required(String value, String message) {
        String normalized = sanitizer.trimToNull(value, 2000);
        if (normalized == null) throw new BusinessConflictException(message);
        return normalized;
    }

    public record DataInventoryItem(String category, String data, String purpose,
                                    PrivacyLegalBasis legalBasis) {}
}
