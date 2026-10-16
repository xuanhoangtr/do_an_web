package com.utewedding.app.service.port;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Provider boundary. Implementations must never be invoked while a business row lock is held. */
public interface ShippingProvider {
    enum Capability { QUOTE, CREATE, CANCEL, PICKUP, TRACK, LABEL, WEBHOOK }

    String code();
    Set<Capability> capabilities();
    /** Whether an account-less legacy platform shipment can be scoped to this provider shop. */
    default boolean matchesPlatformShopId(String providerShopId) { return false; }
    Quote quote(QuoteRequest request);
    ShipmentResult create(CreateShipment request);
    ShipmentResult cancel(OperationRequest request);
    ShipmentResult requestPickup(OperationRequest request);
    TrackingResult track(OperationRequest request);

    /** Lookup by the stable client request key after a timeout with an unknown outcome. */
    default ClientRequestLookup findByClientRequestId(String operation, String clientRequestId) {
        return new ClientRequestLookup(LookupStatus.UNKNOWN, null);
    }

    default ClientRequestLookup findByClientRequestId(String operation, String clientRequestId,
                                                       GhnShipmentContext context) {
        return findByClientRequestId(operation, clientRequestId);
    }

    /**
     * Verify a provider shipment against the original order before an ambiguous CREATE is completed.
     * Providers without a verification API must leave the operation unresolved.
     */
    default ShipmentResult verifyCreatedShipment(VerificationRequest request) {
        throw new UnsupportedOperationException("Provider cannot verify a created shipment");
    }

    default WebhookEvent verifyAndParseWebhook(Map<String, String> headers, byte[] rawBody) {
        throw new UnsupportedOperationException("Provider does not support webhooks");
    }

    record QuoteRequest(long merchandiseAmount, String currency, ShippingAddressRequest destination,
                        String cartHash, String destinationHash, ShippingParcel parcel, long codAmount) {
        public QuoteRequest(long merchandiseAmount, String currency, ShippingAddressRequest destination,
                            String cartHash, String destinationHash, ShippingParcel parcel) {
            this(merchandiseAmount, currency, destination, cartHash, destinationHash, parcel, 0);
        }
        public QuoteRequest(long merchandiseAmount, String currency, ShippingAddressRequest destination,
                            String cartHash, String destinationHash) {
            this(merchandiseAmount, currency, destination, cartHash, destinationHash, null, 0);
        }
    }
    record Quote(String methodCode, String serviceCode, long fee, String currency,
                 String providerReference, Map<String, Object> metadata, Instant expiresAt) {}
    record CreateShipment(String idempotencyKey, String orderNumber, String fulfillmentReference,
                          long codAmount, ShippingAddressRequest destination, String serviceCode,
                          ShippingParcel parcel, String addressCatalogSource, GhnShipmentContext context) {
        public CreateShipment(String idempotencyKey, String orderNumber, String fulfillmentReference,
                              long codAmount, ShippingAddressRequest destination, String serviceCode,
                              ShippingParcel parcel) {
            this(idempotencyKey, orderNumber, fulfillmentReference, codAmount, destination, serviceCode,
                    parcel, "GHN_STAGING", null);
        }
        public CreateShipment(String idempotencyKey, String orderNumber, String fulfillmentReference,
                              long codAmount, ShippingAddressRequest destination, String serviceCode) {
            this(idempotencyKey, orderNumber, fulfillmentReference, codAmount, destination, serviceCode,
                    null, "GHN_STAGING", null);
        }
        public CreateShipment(String idempotencyKey, String orderNumber, String fulfillmentReference,
                              long codAmount, ShippingAddressRequest destination, String serviceCode,
                              ShippingParcel parcel, String addressCatalogSource) {
            this(idempotencyKey, orderNumber, fulfillmentReference, codAmount, destination, serviceCode,
                    parcel, addressCatalogSource, null);
        }
    }
    record OperationRequest(String idempotencyKey, String providerShipmentId, String trackingNumber,
                            GhnShipmentContext context) {
        public OperationRequest(String idempotencyKey, String providerShipmentId, String trackingNumber) {
            this(idempotencyKey, providerShipmentId, trackingNumber, null);
        }
    }
    record VerificationRequest(String providerShipmentId, String idempotencyKey, long expectedCodAmount,
                               ShippingAddressRequest destination, GhnShipmentContext context) {
        public VerificationRequest(String providerShipmentId, String idempotencyKey, long expectedCodAmount,
                                   ShippingAddressRequest destination) {
            this(providerShipmentId, idempotencyKey, expectedCodAmount, destination, null);
        }
    }
    /** Immutable non-secret provider/account/origin snapshot captured when shipment is requested. */
    record GhnShipmentContext(java.util.UUID shopId, java.util.UUID accountId, String credentialReference,
                              Instant accountUpdatedAt, java.util.UUID routeId, java.util.UUID quoteId,
                              String selectedGroupFingerprint,
                              String externalShopId, java.util.UUID bindingId, int bindingRevision,
                              java.util.UUID pickupAddressRevisionId, int pickupAddressRevision,
                              ShippingAddressRequest pickupAddress,
                              java.util.UUID returnAddressRevisionId, int returnAddressRevision,
                              ShippingAddressRequest returnAddress, ShippingAddressRequest destination,
                              long expectedCodAmount, String environment) {}
    record ShipmentResult(String providerShipmentId, String providerReference, String trackingNumber,
                          String labelReference, FulfillmentStatus status, Map<String, Object> metadata) {}
    record TrackingResult(FulfillmentStatus status, long sequence, Instant eventAt,
                          Map<String, Object> metadata) {}
    enum LookupStatus { FOUND, NOT_FOUND, UNKNOWN }
    record ClientRequestLookup(LookupStatus status, ShipmentResult shipment) {}
    record WebhookEvent(String eventId, String providerShipmentId, String providerStatus,
                        FulfillmentStatus canonicalStatus, Long sequence, Instant eventAt,
                        Map<String, Object> sanitizedPayload, Long collectedAmount,
                        String collectionReference, String providerShopId) {
        public WebhookEvent(String eventId, String providerShipmentId, String providerStatus,
                            FulfillmentStatus canonicalStatus, Long sequence, Instant eventAt,
                            Map<String, Object> sanitizedPayload, Long collectedAmount,
                            String collectionReference) {
            this(eventId, providerShipmentId, providerStatus, canonicalStatus, sequence, eventAt,
                    sanitizedPayload, collectedAmount, collectionReference, null);
        }
    }
}
