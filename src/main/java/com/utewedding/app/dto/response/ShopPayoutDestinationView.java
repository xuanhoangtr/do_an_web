package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ShopPayoutDestinationStatus;
import com.utewedding.app.domain.entity.ShopPayoutDestinationEntity;
import java.time.Instant;
import java.util.UUID;

public record ShopPayoutDestinationView(UUID id, long version, String bankCode, String maskedAccountNumber,
                                       String accountHolderName, ShopPayoutDestinationStatus status,
                                       Instant submittedAt, Instant verifiedAt) {
    public static ShopPayoutDestinationView from(ShopPayoutDestinationEntity entity) {
        return new ShopPayoutDestinationView(entity.getId(), entity.getVersion(), entity.getBankCode(),
                mask(entity.getAccountNumber()), entity.getAccountHolderName(), entity.getStatus(),
                entity.getSubmittedAt(), entity.getVerifiedAt());
    }

    static String mask(String number) {
        if (number == null || number.isBlank()) return "••••";
        int visible = Math.min(4, number.length());
        return "•".repeat(Math.max(4, number.length() - visible)) + number.substring(number.length() - visible);
    }
}
