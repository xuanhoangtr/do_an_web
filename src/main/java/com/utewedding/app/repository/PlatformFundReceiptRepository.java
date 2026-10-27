package com.utewedding.app.repository;

import com.utewedding.app.domain.PlatformFundReceiptEnvironment;
import com.utewedding.app.domain.PlatformFundReceiptSource;
import com.utewedding.app.domain.entity.PlatformFundReceiptEntity;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlatformFundReceiptRepository extends JpaRepository<PlatformFundReceiptEntity, UUID> {
    List<PlatformFundReceiptEntity> findTop50ByOrderByReceivedAtDesc();
    Optional<PlatformFundReceiptEntity> findBySourceCodeAndProviderCodeAndProviderAccountReferenceAndEnvironmentAndSourceIdentity(
            PlatformFundReceiptSource sourceCode, String providerCode, String providerAccountReference,
            PlatformFundReceiptEnvironment environment, String sourceIdentity);

    @Query(value = """
            SELECT shipment.id AS "shipmentId", fulfillment.id AS "fulfillmentId",
                   account.id AS "providerAccountId", account.account_name AS "accountName",
                   account.environment AS "accountEnvironment",
                   shipment.provider_shipment_id AS "providerShipmentId",
                   COALESCE(fulfillment.tracking_number, shipment.tracking_number) AS "trackingNumber",
                   orders.id AS "orderId", orders.order_number AS "orderNumber",
                   shop.display_name AS "shopName", COALESCE(obligation.actual_collected, 0) AS "grossAmount",
                   obligation.expected_collectable AS "expectedCodAmount",
                   obligation.collected_at AS "collectedAt"
            FROM fulfillment_shipments shipment
            JOIN order_fulfillments fulfillment ON fulfillment.id = shipment.fulfillment_id
            JOIN orders ON orders.id = fulfillment.order_id AND orders.shop_id = fulfillment.shop_id
            JOIN shops shop ON shop.id = orders.shop_id AND shop.kind = 'VENDOR'
            JOIN order_shipping_snapshots shipping ON shipping.order_id = orders.id
            JOIN cod_obligations obligation ON obligation.fulfillment_id = fulfillment.id
            JOIN shipping_provider_accounts account
              ON account.id = shipment.provider_account_id
             AND upper(btrim(account.provider_code)) = 'GHN'
            JOIN shop_shipping_bindings binding
              ON binding.id = (shipment.provider_context_snapshot ->> 'bindingId')::uuid
             AND binding.shop_id = shop.id AND binding.provider_account_id = account.id
            WHERE upper(btrim(orders.payment_method)) = 'COD'
              AND orders.status NOT IN ('CANCELLED', 'EXPIRED')
              AND orders.payment_status NOT IN ('FAILED', 'NOT_COLLECTED')
              AND upper(btrim(shipping.provider_code)) = 'GHN'
              AND fulfillment.status = 'DELIVERED' AND shipment.status = 'DELIVERED'
              AND upper(btrim(shipment.provider_code)) = 'GHN' AND shipment.provider_shipment_id IS NOT NULL
              AND shipment.provider_context_snapshot IS NOT NULL
              AND shipment.provider_context_snapshot ->> 'shopId' = shop.id::text
              AND shipment.provider_context_snapshot ->> 'accountId' = account.id::text
              AND shipment.provider_context_snapshot ->> 'credentialReference' = account.credential_reference
              AND (shipment.provider_context_snapshot ->> 'accountUpdatedAt')::timestamptz = account.updated_at
              AND shipment.provider_context_snapshot ->> 'environment' = account.environment
              AND (shipment.provider_context_snapshot ->> 'expectedCodAmount')::bigint = obligation.expected_collectable
              AND account.status = 'VERIFIED' AND account.verified_at IS NOT NULL
              AND account.environment = 'PRODUCTION'
              AND binding.status = 'VERIFIED'
              AND (
                  (binding.route_mode = 'LEGACY_ROUTES' AND binding.route_coverage_verified)
                  OR (binding.route_mode = 'API_QUOTE'
                      AND shipping.pricing_mode = 'PROVIDER_QUOTE'
                      AND shipping.shipping_binding_id = binding.id
                      AND shipping.shipping_binding_revision = binding.revision
                      AND shipping.shipping_provider_account_id = account.id
                      AND shipping.shipping_provider_account_updated_at = account.updated_at
                      AND (shipment.provider_context_snapshot ->> 'routeId')
                          IS NOT DISTINCT FROM shipping.shipping_route_id::text)
              )
              AND binding.cod_beneficiary_verified AND binding.cod_beneficiary_verified_at IS NOT NULL
              AND binding.cod_beneficiary_evidence_redacted IS NOT NULL
              AND (shipment.provider_context_snapshot ->> 'bindingRevision')::integer = binding.revision
              AND ((obligation.status = 'COLLECTED' AND obligation.actual_collected > 0
                    AND obligation.remittance_status = 'PENDING')
                   OR (obligation.status IN ('PENDING', 'AWAITING_AMOUNT')
                       AND obligation.actual_collected IS NULL))
              AND NOT EXISTS (
                  SELECT 1 FROM platform_fund_receipt_allocations allocation
                  WHERE allocation.fulfillment_id = fulfillment.id
                    AND allocation.source_kind IN ('COD_REMITTANCE', 'CARRIER_REMITTANCE')
              )
            ORDER BY obligation.collected_at, shipment.id
            LIMIT 100
            """, nativeQuery = true)
    List<CarrierRemittanceCandidate> findCarrierRemittanceCandidates();

    interface CarrierRemittanceCandidate {
        UUID getShipmentId();
        UUID getFulfillmentId();
        UUID getProviderAccountId();
        String getAccountName();
        String getAccountEnvironment();
        String getProviderShipmentId();
        String getTrackingNumber();
        UUID getOrderId();
        String getOrderNumber();
        String getShopName();
        long getGrossAmount();
        long getExpectedCodAmount();
        java.time.Instant getCollectedAt();
    }
}
