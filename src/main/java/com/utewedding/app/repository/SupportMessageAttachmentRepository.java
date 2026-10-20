package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.SupportMessageAttachmentEntity;
import com.utewedding.app.domain.entity.SupportMessageAttachmentId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportMessageAttachmentRepository extends JpaRepository<SupportMessageAttachmentEntity, SupportMessageAttachmentId> {
    List<SupportMessageAttachmentEntity> findByIdMessageId(UUID messageId);

    @Query("select a from SupportMessageAttachmentEntity a where a.id.messageId in :messageIds")
    List<SupportMessageAttachmentEntity> findByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);
}
