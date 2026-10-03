package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.RsvpEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Canonical repository for the RSVP aggregate. */
public interface RsvpRepository extends JpaRepository<RsvpEntity, UUID> {
    Optional<RsvpEntity> findByGuestId(UUID guestId);
    java.util.List<RsvpEntity> findByWeddingId(UUID weddingId);
    java.util.List<RsvpEntity> findByWeddingIdAndGuestIdIn(UUID weddingId, java.util.Collection<UUID> guestIds);
}
