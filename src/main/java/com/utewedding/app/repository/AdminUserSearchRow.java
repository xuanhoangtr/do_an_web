package com.utewedding.app.repository;

import java.time.Instant;

public interface AdminUserSearchRow {
    String getId();
    String getEmail();
    String getDisplayName();
    String getRole();
    String getStatus();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    Instant getDeletedAt();
}
