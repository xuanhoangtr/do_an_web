package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.service.port.ShippingParcel;
import com.utewedding.app.service.port.ShippingProvider;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminShippingCostService {
    private final OrderShippingSnapshotRepository snapshots;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ShippingParcelFactory parcels;
    private final ShippingProviderRegistry providers;
    private final ObjectMapper json;
    private final ShippingCostPersistence costs;

    public AdminShippingCostService(OrderShippingSnapshotRepository snapshots, OrderRepository orders,
                                    OrderItemRepository items, ShippingParcelFactory parcels,
                                    ShippingProviderRegistry providers, ObjectMapper json, ShippingCostPersistence costs) {
        this.snapshots = snapshots; this.orders = orders; this.items = items;
        this.parcels = parcels; this.providers = providers; this.json = json;
        this.costs = costs;
    }

    public CostView view(UUID orderId) {
        var snapshot = snapshots.findByOrderId(orderId).orElse(null);
        if (snapshot == null) return null;
        long customerFee = orders.findById(orderId).map(com.utewedding.app.domain.entity.OrderEntity::getShippingFee)
                .orElse(snapshot.getQuotedFee());
        if (!"GHN".equalsIgnoreCase(snapshot.getProviderCode())) {
            return new CostView(snapshot.getProviderCode(), customerFee, 0L,
                    customerFee, false, null);
        }
        var cost = costs.find(orderId);
        Long fee = cost == null ? null : cost.fee();
        return new CostView(snapshot.getProviderCode(), customerFee, fee,
                fee == null ? null : customerFee - fee,
                cost != null && "CREATED".equals(cost.source()),
                cost == null ? null : cost.checkedAt().toString());
    }

    public CostView refresh(UUID orderId) {
        var current = view(orderId);
        if (current == null || !"GHN".equalsIgnoreCase(current.provider()) || current.confirmed()) return current;
        var order = orders.findById(orderId).orElseThrow();
        var snapshot = snapshots.findByOrderId(orderId).orElseThrow();
        var metadata = metadata(snapshot.getQuoteMetadata());
        ShippingParcel parcel;
        try {
            parcel = metadata.hasNonNull("parcel")
                    ? json.treeToValue(metadata.get("parcel"), ShippingParcel.class)
                    : parcels.fromOrderItems(items.findByOrderIdOrderByIdAsc(orderId));
        } catch (Exception exception) {
            throw new BusinessConflictException("Không đọc được thông tin kiện hàng để tra cước GHN.");
        }
        var address = new ShippingAddressRequest(order.getShippingRecipientName(), order.getShippingPhone(),
                order.getShippingAddress(), order.getShippingProvinceCode(), order.getShippingWardCode());
        var quote = providers.require("GHN").quote(new ShippingProvider.QuoteRequest(
                Math.max(0, order.getTotalAmount() - order.getShippingFee()), "VND", address, "", "", parcel,
                "COD".equalsIgnoreCase(order.getPaymentMethod()) ? order.getTotalAmount() : 0));
        if (quote.fee() < 0) throw new BusinessConflictException("GHN chưa trả về cước hợp lệ.");
        costs.store(orderId, quote.fee(), "ESTIMATE", parcel);
        return view(orderId);
    }

    private ObjectNode metadata(String value) {
        try {
            var node = json.readTree(value);
            if (node instanceof ObjectNode object) return object;
        } catch (Exception ignored) { }
        return json.createObjectNode();
    }

    public record CostView(String provider, long customerFee, Long carrierFee, Long difference,
                           boolean confirmed, String checkedAt) { }
}
