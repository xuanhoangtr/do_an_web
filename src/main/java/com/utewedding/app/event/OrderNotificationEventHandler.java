package com.utewedding.app.event;

import com.utewedding.app.integration.NotificationService;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.EmailService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends external notifications only after the order transaction commits. */
@Component
public class OrderNotificationEventHandler {

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public OrderNotificationEventHandler(NotificationService notificationService,
                                         UserRepository userRepository,
                                         EmailService emailService) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        notificationService.notifyNewOrder(event.customerUserId(), event.orderNumber(), event.amount(), event.currency());
        if (event.customerUserId() != null) {
            userRepository.findById(event.customerUserId()).ifPresent(user ->
                    emailService.sendOrderNotification(user.getEmail(), event.orderNumber(),
                            event.amount(), event.currency()));
        }
    }
}
