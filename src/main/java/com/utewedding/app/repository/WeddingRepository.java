package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.WeddingEntity;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeddingRepository extends JpaRepository<WeddingEntity, UUID> {
    /** Derived-query equivalent of Prisma's where: { slug }. */
    Optional<WeddingEntity> findBySlug(String slug);

    List<WeddingEntity> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
    org.springframework.data.domain.Page<WeddingEntity> findByOwnerIdOrderByCreatedAtDesc(
            UUID ownerId, org.springframework.data.domain.Pageable pageable);
    Optional<WeddingEntity> findBySlugAndStatus(String slug, com.utewedding.app.domain.WeddingStatus status);
    Optional<WeddingEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
    Optional<WeddingEntity> findByPreviewToken(String previewToken);

    @org.springframework.data.jpa.repository.Query("""
            select w from WeddingEntity w
            where (cast(:query as string) is null or lower(coalesce(w.slug, '')) like lower(concat('%', cast(:query as string), '%'))
                   or lower(coalesce(w.internalTitle, '')) like lower(concat('%', cast(:query as string), '%')))
              and (:status is null or w.status = :status)
            """)
    org.springframework.data.domain.Page<WeddingEntity> search(
            @org.springframework.data.repository.query.Param("query") String query,
            @org.springframework.data.repository.query.Param("status") com.utewedding.app.domain.WeddingStatus status,
            org.springframework.data.domain.Pageable pageable);
}
