package com.utewedding.app.event;

import com.utewedding.app.integration.NotificationService;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class FulfillmentNotificationEventHandler {
    private final NotificationService notificationService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public FulfillmentNotificationEventHandler(NotificationService notificationService,
                                               OrderRepository orderRepository,
                                               UserRepository userRepository) {
        this.notificationService = notificationService;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(FulfillmentChangedEvent event) {
        String orderNumber = orderRepository.findById(event.orderId())
                .map(order -> order.getOrderNumber()).orElse(null);
        String status = event.status() == null ? "UNKNOWN" : event.status().name();
        String message = switch (event.type()) {
            case ASSIGNED -> "Bạn được gán một đơn giao hàng.";
            case DELIVERED -> "Đơn hàng đã được giao thành công.";
            case STATUS_CHANGED -> "Trạng thái fulfillment đã được cập nhật.";
            case CREATED -> "Fulfillment mới đã được tạo.";
        };
        if (event.type() == FulfillmentChangedEvent.Type.ASSIGNED) {
            notificationService.notifyFulfillment(event.shipperId(), event.fulfillmentId(), status, orderNumber, message);
            return;
        }
        if (event.type() == FulfillmentChangedEvent.Type.STATUS_CHANGED
                && "PROCESSING".equals(status)) {
            userRepository.findOperationsUsers(
                    List.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.SUPER_ADMIN))
                    .forEach(user -> notificationService.notifyFulfillment(user.getId(), event.fulfillmentId(),
                            status, orderNumber, "Shop đang chuẩn bị hàng."));
        }
        notificationService.notifyFulfillment(event.customerId(), event.fulfillmentId(), status, orderNumber, message);
    }
}
