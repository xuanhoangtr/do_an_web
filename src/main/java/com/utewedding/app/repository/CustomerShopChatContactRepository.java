package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CustomerShopChatContactEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Customer-owned contacts, keyed by the shop the customer explicitly opened. */
public interface CustomerShopChatContactRepository
        extends JpaRepository<CustomerShopChatContactEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO customer_shop_chat_contacts
                (id, customer_id, shop_id, first_opened_at, last_opened_at, created_at, updated_at, version)
            VALUES (:id, :customerId, :shopId, :openedAt, :openedAt, :openedAt, :openedAt, 0)
            ON CONFLICT (customer_id, shop_id) DO UPDATE
               SET last_opened_at = GREATEST(customer_shop_chat_contacts.last_opened_at, EXCLUDED.last_opened_at),
                   updated_at = GREATEST(customer_shop_chat_contacts.updated_at, EXCLUDED.updated_at),
                   version = customer_shop_chat_contacts.version + 1
            """, nativeQuery = true)
    int upsertOpened(@Param("id") UUID id, @Param("customerId") UUID customerId,
                     @Param("shopId") UUID shopId, @Param("openedAt") Instant openedAt);

    Optional<CustomerShopChatContactEntity> findByCustomerIdAndShopId(UUID customerId, UUID shopId);

    List<CustomerShopChatContactEntity> findByCustomerIdOrderByLastOpenedAtDescIdAsc(UUID customerId);

    @Modifying
    @Query("delete from CustomerShopChatContactEntity c where c.customerId = :customerId")
    int deleteByCustomerId(@Param("customerId") UUID customerId);

    boolean existsByCustomerIdAndShopId(UUID customerId, UUID shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerShopChatContactEntity c where c.customerId = :customerId and c.shopId = :shopId")
    Optional<CustomerShopChatContactEntity> findByCustomerAndShopForUpdate(
            @Param("customerId") UUID customerId, @Param("shopId") UUID shopId);
}
