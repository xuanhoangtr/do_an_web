package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderShippingSnapshotEntity;
import com.utewedding.app.domain.entity.ShippingQuoteEntity;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.repository.ShippingQuoteRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderShippingSnapshotService {
    private final OrderShippingSnapshotRepository snapshots;
    private final ShippingQuoteRepository quotes;
    private final ObjectMapper mapper;

    @Autowired
    public OrderShippingSnapshotService(OrderShippingSnapshotRepository snapshots,
                                        ShippingQuoteRepository quotes, ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.quotes = quotes;
        this.mapper = mapper;
    }

    @Transactional
    public void create(OrderEntity order, UUID quoteId, ShippingAddressRequest destination) {
        if (snapshots.findByOrderId(order.getId()).isPresent()) return;
        if (quoteId == null) {
            throw new com.utewedding.app.exception.BusinessConflictException(
                    "Không thể lưu đơn khi chưa có báo giá giao hàng.");
        }
        ShippingQuoteEntity quote = quotes.findById(quoteId).orElseThrow(() ->
                new com.utewedding.app.exception.BusinessConflictException(
                        "Báo giá giao hàng không còn hợp lệ. Vui lòng cập nhật lại."));
        boolean platformOrder = com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId());
        if ((!platformOrder && !Objects.equals(order.getShopId(), quote.getShopId()))
                || (quote.getShopId() != null && !Objects.equals(order.getShopId(), quote.getShopId()))) {
            throw new com.utewedding.app.exception.BusinessConflictException(
                    "Báo giá giao hàng không thuộc cửa hàng của đơn hàng.");
        }
        OrderShippingSnapshotEntity snapshot = new OrderShippingSnapshotEntity();
        snapshot.setShopId(order.getShopId());
        snapshot.setOrderId(order.getId());
        snapshot.setQuoteId(quote.getId());
        snapshot.setPricingMode(quote.getPricingMode());
        snapshot.setPolicyVersion(quote.getPolicyVersion());
        if (quote.getShopId() != null) {
            snapshot.setOriginAddressRevisionId(quote.getPickupAddressRevisionId());
            snapshot.setOriginAddressRevision(quote.getPickupAddressRevision());
            snapshot.setShippingBindingId(quote.getShippingBindingId());
            snapshot.setShippingBindingRevision(quote.getShippingBindingRevision());
            snapshot.setShippingProviderAccountId(quote.getShippingProviderAccountId());
            snapshot.setShippingProviderAccountUpdatedAt(quote.getShippingProviderAccountUpdatedAt());
            snapshot.setShippingRouteId(quote.getShippingRouteId());
            snapshot.setSelectedGroupFingerprint(quote.getSelectedGroupFingerprint());
        }
        snapshot.setProviderCode(quote.getProviderCode());
        snapshot.setCarrierId(quote.getCarrierId());
        snapshot.setCarrierCode(quote.getCarrierCode());
        snapshot.setCarrierName(quote.getCarrierName());
        snapshot.setMethodCode(quote.getMethodCode());
        snapshot.setServiceCode(quote.getServiceCode());
        snapshot.setQuotedFee(quote.getFee());
        snapshot.setCurrency(quote.getCurrency());
        snapshot.setAddressCatalogSource(quote.getAddressCatalogSource());
        snapshot.setProviderQuoteRef(quote.getProviderQuoteRef());
        snapshot.setQuoteMetadata(quote.getMetadata());
        snapshot.setQuotedAt(quote.getCreatedAt());
        try {
            Map<String, String> destinationSnapshot = new LinkedHashMap<>();
            destinationSnapshot.put("recipientName", safe(destination.recipientName()));
            destinationSnapshot.put("phone", safe(destination.phone()));
            destinationSnapshot.put("address", safe(destination.addressLine()));
            destinationSnapshot.put("provinceCode", safe(destination.provinceCode()));
            destinationSnapshot.put("provinceName", safe(order.getShippingProvinceName()));
            destinationSnapshot.put("wardCode", safe(destination.wardCode()));
            destinationSnapshot.put("wardName", safe(order.getShippingWardName()));
            destinationSnapshot.put("catalogSource", quote.getAddressCatalogSource());
            snapshot.setDestinationSnapshot(mapper.writeValueAsString(destinationSnapshot));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot snapshot shipping destination", exception);
        }
        snapshots.save(snapshot);
    }

    private String safe(String value) { return value == null ? "" : value; }
}
