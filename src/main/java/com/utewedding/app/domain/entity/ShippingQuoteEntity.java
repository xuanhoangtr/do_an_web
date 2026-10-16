package com.utewedding.app.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="shipping_quotes")
public class ShippingQuoteEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="customer_id",nullable=false) private UUID customerId;
    @Column(name="provider_code",nullable=false,length=40) private String providerCode;
    @Column(name="carrier_id") private UUID carrierId;
    @Column(name="carrier_code",length=60) private String carrierCode;
    @Column(name="carrier_name",length=120) private String carrierName;
    @Column(name="carrier_availability_version",nullable=false) private long carrierAvailabilityVersion;
    @Column(name="pricing_mode",nullable=false,length=24) private String pricingMode="LEGACY";
    @Column(name="policy_version",nullable=false) private int policyVersion;
    @Column(name="method_code",nullable=false,length=40) private String methodCode;
    @Column(name="service_code",nullable=false,length=80) private String serviceCode;
    @Column(nullable=false) private long fee;
    @Column(nullable=false,length=3) private String currency="VND";
    @Column(name="cart_hash",nullable=false,length=64) private String cartHash;
    @Column(name="destination_hash",nullable=false,length=64) private String destinationHash;
    @Column(name="address_catalog_source",nullable=false,length=32) private String addressCatalogSource="LOCAL";
    @Column(name="shop_id") private UUID shopId;
    @Column(name="selected_group_fingerprint",length=64) private String selectedGroupFingerprint;
    @Column(name="pickup_address_revision_id") private UUID pickupAddressRevisionId;
    @Column(name="pickup_address_revision") private Integer pickupAddressRevision;
    @Column(name="shipping_binding_id") private UUID shippingBindingId;
    @Column(name="shipping_binding_revision") private Integer shippingBindingRevision;
    @Column(name="shipping_provider_account_id") private UUID shippingProviderAccountId;
    @Column(name="shipping_provider_account_updated_at") private Instant shippingProviderAccountUpdatedAt;
    @Column(name="shipping_route_id") private UUID shippingRouteId;
    @Column(name="provider_quote_ref",length=190) private String providerQuoteRef;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",nullable=false) private String metadata="{}";
    @Column(name="expires_at",nullable=false) private Instant expiresAt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @PrePersist void create(){ if(createdAt==null) createdAt=Instant.now(); }
    public UUID getId(){return id;} public UUID getCustomerId(){return customerId;} public void setCustomerId(UUID v){customerId=v;}
    public String getProviderCode(){return providerCode;} public void setProviderCode(String v){providerCode=v;}
    public UUID getCarrierId(){return carrierId;} public void setCarrierId(UUID v){carrierId=v;}
    public String getCarrierCode(){return carrierCode;} public void setCarrierCode(String v){carrierCode=v;}
    public String getCarrierName(){return carrierName;} public void setCarrierName(String v){carrierName=v;}
    public long getCarrierAvailabilityVersion(){return carrierAvailabilityVersion;} public void setCarrierAvailabilityVersion(long v){carrierAvailabilityVersion=v;}
    public String getPricingMode(){return pricingMode;} public void setPricingMode(String v){pricingMode=v;}
    public int getPolicyVersion(){return policyVersion;} public void setPolicyVersion(int v){policyVersion=v;}
    public String getMethodCode(){return methodCode;} public void setMethodCode(String v){methodCode=v;}
    public String getServiceCode(){return serviceCode;} public void setServiceCode(String v){serviceCode=v;}
    public long getFee(){return fee;} public void setFee(long v){fee=v;} public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;}
    public String getCartHash(){return cartHash;} public void setCartHash(String v){cartHash=v;} public String getDestinationHash(){return destinationHash;} public void setDestinationHash(String v){destinationHash=v;}
    public String getAddressCatalogSource(){return addressCatalogSource;} public void setAddressCatalogSource(String v){addressCatalogSource=v;}
    public UUID getShopId(){return shopId;} public void setShopId(UUID v){shopId=v;}
    public String getSelectedGroupFingerprint(){return selectedGroupFingerprint;} public void setSelectedGroupFingerprint(String v){selectedGroupFingerprint=v;}
    public UUID getPickupAddressRevisionId(){return pickupAddressRevisionId;} public void setPickupAddressRevisionId(UUID v){pickupAddressRevisionId=v;}
    public Integer getPickupAddressRevision(){return pickupAddressRevision;} public void setPickupAddressRevision(Integer v){pickupAddressRevision=v;}
    public UUID getShippingBindingId(){return shippingBindingId;} public void setShippingBindingId(UUID v){shippingBindingId=v;}
    public Integer getShippingBindingRevision(){return shippingBindingRevision;} public void setShippingBindingRevision(Integer v){shippingBindingRevision=v;}
    public UUID getShippingProviderAccountId(){return shippingProviderAccountId;} public void setShippingProviderAccountId(UUID v){shippingProviderAccountId=v;}
    public Instant getShippingProviderAccountUpdatedAt(){return shippingProviderAccountUpdatedAt;} public void setShippingProviderAccountUpdatedAt(Instant v){shippingProviderAccountUpdatedAt=v;}
    public UUID getShippingRouteId(){return shippingRouteId;} public void setShippingRouteId(UUID v){shippingRouteId=v;}
    public String getProviderQuoteRef(){return providerQuoteRef;} public void setProviderQuoteRef(String v){providerQuoteRef=v;} public String getMetadata(){return metadata;} public void setMetadata(String v){metadata=v;}
    public Instant getExpiresAt(){return expiresAt;} public void setExpiresAt(Instant v){expiresAt=v;} public Instant getCreatedAt(){return createdAt;}
}
