package com.utewedding.app.integration;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.entity.UserNotificationEntity;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.UserNotificationRepository;
import com.utewedding.app.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists notifications, then publishes them to private STOMP user destinations. */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final UserNotificationRepository notifications;
    private final UserRepository users;
    private final OrderFulfillmentRepository fulfillments;
    public NotificationService(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate,
                (UserNotificationRepository) null,
                (UserRepository) null,
                (OrderFulfillmentRepository) null);
    }

    @Autowired
    public NotificationService(
            SimpMessagingTemplate messagingTemplate,
            ObjectProvider<UserNotificationRepository> notifications,
            ObjectProvider<UserRepository> users,
            ObjectProvider<OrderFulfillmentRepository> fulfillments) {
        this.messagingTemplate = messagingTemplate;
        this.notifications = notifications.getIfAvailable();
        this.users = users.getIfAvailable();
        this.fulfillments = fulfillments.getIfAvailable();
    }

    private NotificationService(SimpMessagingTemplate messagingTemplate,
                                UserNotificationRepository notifications,
                                UserRepository users,
                                OrderFulfillmentRepository fulfillments) {
        this.messagingTemplate = messagingTemplate;
        this.notifications = notifications;
        this.users = users;
        this.fulfillments = fulfillments;
    }

    /** Sends a new order only to explicitly selected operator users. */
    @Transactional
    public void notifyNewOrder(
            UUID customerUserId,
            String orderNumber,
            long amount,
            String currency) {
        NewOrderNotification payload = new NewOrderNotification(
                orderNumber, amount, currency, Instant.now());
        remember(customerUserId, "ORDER", "Đơn hàng mới",
                "Đơn " + orderNumber + " đã được tạo.", orderTarget(customerUserId));
        sendPrivate("/queue/orders", customerUserId == null ? null : customerUserId.toString(), payload);
    }

    /** Sends a guest wish only to the owner of the wedding. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void notifyWeddingWish(
            UUID ownerUserId,
            UUID weddingId,
            UUID wishId,
            String senderName,
            String message) {
        WeddingWishNotification payload = new WeddingWishNotification(
                weddingId, wishId, senderName, message, Instant.now());
        remember(ownerUserId, "WISH", "Lời chúc mới",
                senderName + " vừa gửi lời chúc.", weddingId == null ? "/wedding" : "/wedding/" + weddingId + "/wishes", true);
        sendAfterCommit("/queue/wishes", ownerUserId == null ? null : ownerUserId.toString(), payload);
    }

    @Transactional
    public void notifyFulfillment(UUID recipientUserId, UUID fulfillmentId, String status,
                                  String orderNumber, String message) {
        remember(recipientUserId, "FULFILLMENT", "Cập nhật giao hàng", message,
                fulfillmentTarget(recipientUserId, fulfillmentId));
        sendPrivate("/queue/fulfillment", recipientUserId == null ? null : recipientUserId.toString(),
                new FulfillmentNotification(fulfillmentId, orderNumber, status, message, Instant.now()));
    }

    @Transactional
    public void notifySupport(UUID recipientUserId, UUID ticketId, String message) {
        remember(recipientUserId, "SUPPORT", "Cập nhật hỗ trợ", message,
                ticketId == null ? "/support" : supportTarget(recipientUserId) + ticketId);
        sendPrivate("/queue/support", recipientUserId == null ? null : recipientUserId.toString(),
                new SupportNotification(ticketId, message, Instant.now()));
    }

    private void remember(UUID recipientId, String type, String title, String body, String targetUrl) {
        remember(recipientId, type, title, body, targetUrl, false);
    }

    private void remember(UUID recipientId, String type, String title, String body, String targetUrl, boolean afterCommit) {
        if (recipientId == null || notifications == null) return;
        try {
            UserNotificationEntity row = new UserNotificationEntity();
            row.setRecipientId(recipientId);
            row.setType(type);
            row.setTitle(limit(title, 160));
            row.setBody(limit(body == null ? title : body, 1000));
            row.setTargetUrl(limit(targetUrl, 500));
            notifications.save(row);
            var signal = new UnreadNotificationSignal(Instant.now());
            if (afterCommit) sendAfterCommit("/queue/notifications", recipientId.toString(), signal);
            else sendPrivate("/queue/notifications", recipientId.toString(), signal);
        } catch (RuntimeException exception) {
            log.warn("Could not persist notification for {}", recipientId, exception);
        }
    }

    private String fulfillmentTarget(UUID recipientId, UUID fulfillmentId) {
        if (fulfillmentId == null) return "/notifications";
        UserRole role = users == null || recipientId == null ? null
                : users.findById(recipientId).map(user -> user.getRole()).orElse(null);
        if (role == UserRole.SHIPPER) return "/shipper/fulfillments/" + fulfillmentId;
        if (role == UserRole.ADMIN || role == UserRole.MANAGER || role == UserRole.SUPER_ADMIN) {
            return "/admin/fulfillments/" + fulfillmentId;
        }
        if (role != UserRole.USER && role != UserRole.CUSTOMER) return "/notifications";
        if (fulfillments == null) return "/orders/history";
        return fulfillments.findById(fulfillmentId)
                .map(row -> "/orders/" + row.getOrderId())
                .orElse("/orders/history");
    }

    private String supportTarget(UUID recipient){var role=users==null?null:users.findById(recipient).map(u->u.getRole()).orElse(null);return role!=null&&java.util.Set.of(UserRole.ADMIN,UserRole.MANAGER,UserRole.SUPER_ADMIN).contains(role)?"/admin/support/":"/support/";}
    @Transactional public void notifyReturn(UUID recipient,UUID caseId){remember(recipient,"RETURN","Đã xử lý trả hàng","Yêu cầu trả hàng đã được xử lý.","/returns/"+caseId);}
    private String orderTarget(UUID recipientId) {
        if (users == null || recipientId == null) return "/notifications";
        UserRole role = users.findById(recipientId).map(user -> user.getRole()).orElse(null);
        if (role == UserRole.USER || role == UserRole.CUSTOMER) return "/orders/history";
        if (role == UserRole.SHIPPER) return "/shipper/board";
        if (role == UserRole.ADMIN || role == UserRole.MANAGER || role == UserRole.SUPER_ADMIN) return "/admin/orders";
        return "/notifications";
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private void sendPrivate(String destination, String userId, Object payload) {
        if (userId != null && !userId.isBlank()) {
            messagingTemplate.convertAndSendToUser(userId, destination, payload);
        }
    }

    private void sendAfterCommit(String destination, String userId, Object payload) {
        if (userId != null && !userId.isBlank()) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() {
                                messagingTemplate.convertAndSendToUser(userId, destination, payload);
                            }
                        });
                return;
            }
            messagingTemplate.convertAndSendToUser(userId, destination, payload);
        }
    }

    public record NewOrderNotification(
            String orderNumber,
            long amount,
            String currency,
            Instant occurredAt) {
    }

    public record WeddingWishNotification(
            UUID weddingId,
            UUID wishId,
            String senderName,
            String message,
            Instant occurredAt) {
    }

    public record FulfillmentNotification(
            UUID fulfillmentId,
            String orderNumber,
            String status,
            String message,
            Instant occurredAt) {
    }

    public record SupportNotification(UUID ticketId, String message, Instant occurredAt) {
    }

    public record UnreadNotificationSignal(Instant occurredAt) {
    }
}
