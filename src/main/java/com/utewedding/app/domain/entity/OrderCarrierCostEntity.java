package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ShopDefaults;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_carrier_costs")
public class OrderCarrierCostEntity {
    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId = ShopDefaults.PLATFORM_SHOP_ID;

    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;

    @Column(name = "fee", nullable = false)
    private long fee;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected OrderCarrierCostEntity() { }

    public UUID getOrderId() { return orderId; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public String getProviderCode() { return providerCode; }
    public long getFee() { return fee; }
    public String getCurrency() { return currency; }
    public String getSource() { return source; }
    public Instant getCheckedAt() { return checkedAt; }
}
