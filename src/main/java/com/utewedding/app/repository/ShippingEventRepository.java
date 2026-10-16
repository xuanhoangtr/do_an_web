package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShippingEventEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShippingEventRepository extends JpaRepository<ShippingEventEntity, UUID> {
    Optional<ShippingEventEntity> findByProviderCodeAndProviderAccountIdAndProviderShopIdAndProviderEventId(
            String code, UUID accountId, String shopId, String id);

    Optional<ShippingEventEntity> findByProviderCodeAndProviderAccountIdAndProviderShopIdIsNullAndProviderEventId(
            String code, UUID accountId, String id);

    Optional<ShippingEventEntity> findByProviderCodeAndProviderShopIdAndProviderEventIdAndProviderAccountIdIsNull(
            String code, String shopId, String id);

    List<ShippingEventEntity> findAllByProviderCodeAndProviderShopIdAndProviderEventIdAndProviderAccountIdIsNotNull(
            String code, String shopId, String id);

    Optional<ShippingEventEntity> findByProviderCodeAndProviderEventIdAndProviderShopIdIsNullAndProviderAccountIdIsNull(
            String code, String id);

    @Query(value = """
            select event.*
              from shipping_events event
             where event.provider_code = 'GHN'
               and event.provider_event_id = :eventId
               and event.provider_shop_id is null
               and event.provider_account_id is null
               and coalesce(
                       nullif(btrim(event.sanitized_payload ->> 'ShopId'), ''),
                       nullif(btrim(event.sanitized_payload ->> 'ShopID'), ''),
                       nullif(btrim(event.sanitized_payload ->> 'shop_id'), '')
                   ) = btrim(:providerShopId)
             order by event.received_at asc
             limit 1
            """, nativeQuery = true)
    Optional<ShippingEventEntity> findLegacyGhnAccountlessByEventIdAndPayloadShopId(
            @Param("eventId") String eventId, @Param("providerShopId") String providerShopId);

    @Query(value = """
            select event.*
              from shipping_events event
             where event.provider_code = 'GHN'
               and event.provider_account_id = :accountId
               and event.provider_event_id = :eventId
               and event.provider_shop_id is null
               and coalesce(
                       nullif(btrim(event.sanitized_payload ->> 'ShopId'), ''),
                       nullif(btrim(event.sanitized_payload ->> 'ShopID'), ''),
                       nullif(btrim(event.sanitized_payload ->> 'shop_id'), '')
                   ) = btrim(:providerShopId)
             order by event.received_at asc
             limit 1
            """, nativeQuery = true)
    Optional<ShippingEventEntity> findLegacyGhnAccountScopedByAccountIdAndEventIdAndPayloadShopId(
            @Param("accountId") UUID accountId, @Param("eventId") String eventId,
            @Param("providerShopId") String providerShopId);

    @Query(value = """
            select event.*
              from shipping_events event
             where event.provider_code = 'GHN'
               and event.provider_account_id is not null
               and event.provider_event_id = :eventId
               and event.provider_shop_id is null
               and coalesce(
                       nullif(btrim(event.sanitized_payload ->> 'ShopId'), ''),
                       nullif(btrim(event.sanitized_payload ->> 'ShopID'), ''),
                       nullif(btrim(event.sanitized_payload ->> 'shop_id'), '')
                   ) = btrim(:providerShopId)
             order by event.received_at asc
            """, nativeQuery = true)
    List<ShippingEventEntity> findAllLegacyGhnAccountScopedByEventIdAndPayloadShopId(
            @Param("eventId") String eventId, @Param("providerShopId") String providerShopId);

    List<ShippingEventEntity> findTop100ByOutcomeInOrderByReceivedAtAsc(Collection<String> outcomes);

    @Query(value = """
            select event.*
              from shipping_events event
             where event.outcome in (:outcomes)
               and event.provider_code = 'GHN'
               and not exists (
                   select 1 from shipping_event_reconciliations reconciliation
                    where reconciliation.shipping_event_id = event.id
               )
             order by event.received_at asc
             limit 100
            """, nativeQuery = true)
    List<ShippingEventEntity> findTop100UnreconciledByOutcomeInOrderByReceivedAtAsc(
            @Param("outcomes") Collection<String> outcomes);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from ShippingEventEntity event where event.id = :id")
    Optional<ShippingEventEntity> findByIdForUpdate(@Param("id") UUID id);
}
