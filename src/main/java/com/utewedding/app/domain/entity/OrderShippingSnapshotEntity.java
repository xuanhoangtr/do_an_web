package com.utewedding.app.domain.entity;
import com.utewedding.app.domain.ShopDefaults;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes;
@Entity @Table(name="order_shipping_snapshots")
public class OrderShippingSnapshotEntity {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="shop_id",nullable=false,updatable=false) private UUID shopId=ShopDefaults.PLATFORM_SHOP_ID;
 @Column(name="order_id",nullable=false,unique=true) private UUID orderId; @Column(name="quote_id") private UUID quoteId;
 @Column(name="method_code",nullable=false,length=40) private String methodCode; @Column(name="provider_code",nullable=false,length=40) private String providerCode;
 @Column(name="carrier_id") private UUID carrierId; @Column(name="carrier_code",length=60) private String carrierCode;
 @Column(name="carrier_name",length=120) private String carrierName;
 @Column(name="pricing_mode",nullable=false,length=24) private String pricingMode="LEGACY";
 @Column(name="policy_version",nullable=false) private int policyVersion;
 @Column(name="service_code",nullable=false,length=80) private String serviceCode; @Column(name="quoted_fee",nullable=false) private long quotedFee;
 @Column(name="address_catalog_source",nullable=false,length=32) private String addressCatalogSource="LOCAL";
 @Column(nullable=false,length=3) private String currency="VND";
 @JdbcTypeCode(SqlTypes.JSON) @Column(name="destination_snapshot",columnDefinition="jsonb",nullable=false) private String destinationSnapshot;
 @Column(name="provider_quote_ref",length=190) private String providerQuoteRef;
 @Column(name="origin_address_revision_id") private UUID originAddressRevisionId;
 @Column(name="origin_address_revision") private Integer originAddressRevision;
 @Column(name="shipping_binding_id") private UUID shippingBindingId;
 @Column(name="shipping_binding_revision") private Integer shippingBindingRevision;
 @Column(name="shipping_provider_account_id") private UUID shippingProviderAccountId;
 @Column(name="shipping_provider_account_updated_at") private Instant shippingProviderAccountUpdatedAt;
 @Column(name="shipping_route_id") private UUID shippingRouteId;
 @Column(name="selected_group_fingerprint",length=64) private String selectedGroupFingerprint;
 @JdbcTypeCode(SqlTypes.JSON) @Column(name="quote_metadata",columnDefinition="jsonb",nullable=false) private String quoteMetadata="{}";
 @Column(name="quoted_at",nullable=false) private Instant quotedAt; @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
 @PrePersist void create(){if(shopId==null)shopId=ShopDefaults.PLATFORM_SHOP_ID;if(createdAt==null)createdAt=Instant.now();}
 public UUID getId(){return id;} public UUID getShopId(){return shopId;} public void setShopId(UUID v){shopId=v;} public UUID getOrderId(){return orderId;} public void setOrderId(UUID v){orderId=v;} public UUID getQuoteId(){return quoteId;} public void setQuoteId(UUID v){quoteId=v;}
 public String getPricingMode(){return pricingMode;} public void setPricingMode(String v){pricingMode=v;} public int getPolicyVersion(){return policyVersion;} public void setPolicyVersion(int v){policyVersion=v;}
 public void setMethodCode(String v){methodCode=v;} public String getMethodCode(){return methodCode;} public void setProviderCode(String v){providerCode=v;} public String getProviderCode(){return providerCode;}
 public UUID getCarrierId(){return carrierId;} public void setCarrierId(UUID v){carrierId=v;} public String getCarrierCode(){return carrierCode;} public void setCarrierCode(String v){carrierCode=v;} public String getCarrierName(){return carrierName;} public void setCarrierName(String v){carrierName=v;}
 public void setServiceCode(String v){serviceCode=v;} public String getServiceCode(){return serviceCode;} public void setQuotedFee(long v){quotedFee=v;} public long getQuotedFee(){return quotedFee;}
 public String getAddressCatalogSource(){return addressCatalogSource;} public void setAddressCatalogSource(String v){addressCatalogSource=v;}
 public void setCurrency(String v){currency=v;} public void setDestinationSnapshot(String v){destinationSnapshot=v;} public String getDestinationSnapshot(){return destinationSnapshot;}
 public void setProviderQuoteRef(String v){providerQuoteRef=v;} public void setQuoteMetadata(String v){quoteMetadata=v;} public String getQuoteMetadata(){return quoteMetadata;} public void setQuotedAt(Instant v){quotedAt=v;}
 public UUID getOriginAddressRevisionId(){return originAddressRevisionId;} public void setOriginAddressRevisionId(UUID v){originAddressRevisionId=v;}
 public Integer getOriginAddressRevision(){return originAddressRevision;} public void setOriginAddressRevision(Integer v){originAddressRevision=v;}
 public UUID getShippingBindingId(){return shippingBindingId;} public void setShippingBindingId(UUID v){shippingBindingId=v;}
 public Integer getShippingBindingRevision(){return shippingBindingRevision;} public void setShippingBindingRevision(Integer v){shippingBindingRevision=v;}
 public UUID getShippingProviderAccountId(){return shippingProviderAccountId;} public void setShippingProviderAccountId(UUID v){shippingProviderAccountId=v;}
 public Instant getShippingProviderAccountUpdatedAt(){return shippingProviderAccountUpdatedAt;} public void setShippingProviderAccountUpdatedAt(Instant v){shippingProviderAccountUpdatedAt=v;}
 public UUID getShippingRouteId(){return shippingRouteId;} public void setShippingRouteId(UUID v){shippingRouteId=v;}
 public String getSelectedGroupFingerprint(){return selectedGroupFingerprint;} public void setSelectedGroupFingerprint(String v){selectedGroupFingerprint=v;}
}
