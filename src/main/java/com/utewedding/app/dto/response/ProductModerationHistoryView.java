package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ProductModerationStatus;
import java.time.Instant;
import java.util.UUID;

public record ProductModerationHistoryView(UUID id, ProductModerationStatus fromStatus,
                                           ProductModerationStatus toStatus, String reason,
                                           UUID actorUserId, Instant occurredAt) { }
