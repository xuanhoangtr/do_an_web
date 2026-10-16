package com.utewedding.app.service.impl;

import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.OrderCarrierCostRepository;
import com.utewedding.app.service.port.ShippingParcel;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShippingCostPersistence {
    private final OrderCarrierCostRepository costs;

    public ShippingCostPersistence(OrderCarrierCostRepository costs) {
        this.costs = costs;
    }

    public CarrierCost find(UUID orderId) {
        return costs.findById(orderId)
                .map(cost -> new CarrierCost(cost.getFee(), cost.getSource(), cost.getCheckedAt()))
                .orElse(null);
    }

    @Transactional
    public void store(UUID orderId, long fee, String source, ShippingParcel ignoredParcel) {
        if (fee < 0) throw new BusinessConflictException("Cước nhà vận chuyển không hợp lệ.");
        if (!"ESTIMATE".equals(source) && !"CREATED".equals(source)) {
            throw new BusinessConflictException("Nguồn chi phí nhà vận chuyển không hợp lệ.");
        }
        costs.upsertGhnCost(orderId, fee, source);
    }

    public record CarrierCost(long fee, String source, Instant checkedAt) { }
}
