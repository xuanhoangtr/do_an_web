package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.SupportMessageAttachmentEntity;
import com.utewedding.app.domain.entity.SupportMessageAttachmentId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Attachment reads are constrained to the shop scope, even after ticket authorization. */
public interface ShopSupportAttachmentRepository extends JpaRepository<SupportMessageAttachmentEntity,
        SupportMessageAttachmentId> {
    @Query("select a from SupportMessageAttachmentEntity a where a.shopId = :shopId "
            + "and a.id.messageId in :messageIds")
    List<SupportMessageAttachmentEntity> findByShopIdAndMessageIds(@Param("shopId") UUID shopId,
            @Param("messageIds") Collection<UUID> messageIds);

    boolean existsByShopIdAndIdMessageIdAndIdManagedMediaId(UUID shopId, UUID messageId, UUID mediaId);

    boolean existsByShopIdAndIdMessageId(UUID shopId, UUID messageId);
}
