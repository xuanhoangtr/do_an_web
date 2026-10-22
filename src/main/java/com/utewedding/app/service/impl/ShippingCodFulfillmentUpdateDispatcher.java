package com.utewedding.app.service.impl;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.entity.OutboxEventEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.OutboxDispatcher;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Replays COD consequences of delivered/returned provider updates from the transactional outbox. */
@Component
public class ShippingCodFulfillmentUpdateDispatcher implements OutboxDispatcher {
    public static final String EVENT = "SHIPPING_COD_FULFILLMENT_UPDATE";
    private final CodReconciliationService cod;

    public ShippingCodFulfillmentUpdateDispatcher(CodReconciliationService cod) {
        this.cod = cod;
    }

    @Override
    public boolean supports(String type) {
        return EVENT.equals(type);
    }

    @Override
    public void dispatch(OutboxEventEntity event) {
        String[] values = event.getPayload().split("\\|", -1);
        if (values.length != 3) throw new BusinessConflictException("Thông điệp cập nhật COD giao hàng không hợp lệ.");
        UUID orderId;
        UUID fulfillmentId;
        FulfillmentStatus status;
        try {
            orderId = UUID.fromString(values[0]);
            fulfillmentId = UUID.fromString(values[1]);
            status = FulfillmentStatus.valueOf(values[2]);
        } catch (IllegalArgumentException exception) {
            throw new BusinessConflictException("Thông điệp cập nhật COD giao hàng không hợp lệ.");
        }
        RequestContext context = new RequestContext(null, "shipping-cod-outbox", event.getId().toString(), "SYSTEM");
        if (status == FulfillmentStatus.DELIVERED) {
            // GHN's CODAmount is the expected amount, not evidence that cash was collected.
            cod.recordDelivered(orderId, fulfillmentId, null, null, context);
        } else if (status == FulfillmentStatus.RETURNED) {
            cod.recordReturned(orderId, fulfillmentId, context);
        } else {
            throw new BusinessConflictException("Thông điệp COD chỉ hợp lệ cho giao thành công hoặc hoàn hàng.");
        }
    }
}
