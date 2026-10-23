package com.utewedding.app.service.web;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;

public record AdminUserFilter(String query, UserRole role, UserStatus status, AccountState accountState) {
    public AdminUserFilter {
        query = query == null ? "" : query.trim();
        if (query.length() > 320) query = query.substring(0, 320);
        accountState = accountState == null ? AccountState.ACTIVE : accountState;
    }

    public enum AccountState {
        ACTIVE, DELETED, ALL
    }
}
