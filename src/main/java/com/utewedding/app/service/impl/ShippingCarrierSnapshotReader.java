package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.OrderShippingSnapshotEntity;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ShippingCarrierSnapshotReader {
    private final OrderShippingSnapshotRepository snapshots;

    public ShippingCarrierSnapshotReader(OrderShippingSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    public Map<UUID, CarrierPresentation> forOrders(Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return Map.of();
        Map<UUID, CarrierPresentation> result = new LinkedHashMap<>();
        snapshots.findByOrderIdIn(orderIds).forEach(snapshot ->
                result.put(snapshot.getOrderId(), presentation(snapshot)));
        return Map.copyOf(result);
    }

    public CarrierPresentation forOrder(UUID orderId) {
        if (orderId == null) return CarrierPresentation.unknown();
        return snapshots.findByOrderId(orderId).map(ShippingCarrierSnapshotReader::presentation)
                .orElseGet(CarrierPresentation::unknown);
    }

    private static CarrierPresentation presentation(OrderShippingSnapshotEntity snapshot) {
        String providerCode = clean(snapshot.getProviderCode());
        String savedName = clean(snapshot.getCarrierName());
        String name = savedName != null ? savedName
                : "GHN".equalsIgnoreCase(providerCode) ? "GHN"
                : "INTERNAL".equalsIgnoreCase(providerCode) ? "Giao hàng nội bộ"
                : "Chưa xác định nhà vận chuyển";
        return new CarrierPresentation(name, clean(snapshot.getCarrierCode()), providerCode,
                snapshot.getQuotedFee());
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CarrierPresentation(String name, String code, String providerCode, long quotedFee) {
        public static CarrierPresentation unknown() {
            return new CarrierPresentation("Chưa xác định nhà vận chuyển", null, null, 0);
        }
    }
}
