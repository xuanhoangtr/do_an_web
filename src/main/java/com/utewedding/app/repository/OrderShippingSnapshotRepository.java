/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.springframework.data.jpa.repository.JpaRepository
 */
package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.OrderShippingSnapshotEntity;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderShippingSnapshotRepository
extends JpaRepository<OrderShippingSnapshotEntity, UUID> {
    public Optional<OrderShippingSnapshotEntity> findByOrderId(UUID var1);
    public List<OrderShippingSnapshotEntity> findByOrderIdIn(Collection<UUID> orderIds);
}
