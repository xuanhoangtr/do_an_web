package com.utewedding.app.service.port;

import java.util.UUID;

/**
 * Isolated provider boundary for shop-owned shipping credentials. Implementations must resolve the
 * credential reference in a server-side secret store and must never use the platform GHN client.
 */
public interface ShopShippingQuoteProvider {
    ShippingProvider.Quote quote(Request request);

    record Request(UUID shopId, UUID bindingId, int bindingRevision, UUID providerAccountId,
                   String credentialReference, String externalShopId, String catalogSource,
                   PickupAddress pickup, PickupAddress returnAddress,
                   DestinationAddress destination, ShippingParcel parcel) {
        public Request(UUID shopId, UUID bindingId, int bindingRevision, UUID providerAccountId,
                       String credentialReference, String externalShopId, String catalogSource,
                       PickupAddress pickup, DestinationAddress destination, ShippingParcel parcel) {
            this(shopId, bindingId, bindingRevision, providerAccountId, credentialReference,
                    externalShopId, catalogSource, pickup, pickup, destination, parcel);
        }
    }

    record PickupAddress(String provinceCode, String provinceName, String wardCode, String wardName,
                         String addressLine, String contactName, String contactPhone) { }

    record DestinationAddress(String recipientName, String phone, String addressLine,
                              String provinceCode, String provinceName, String wardCode, String wardName) { }
}
