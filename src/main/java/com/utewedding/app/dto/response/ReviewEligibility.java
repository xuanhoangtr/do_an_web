package com.utewedding.app.dto.response;

public record ReviewEligibility(boolean canReview, String reason) {
    public static ReviewEligibility allowed() {
        return new ReviewEligibility(true, null);
    }

    public static ReviewEligibility denied(String reason) {
        return new ReviewEligibility(false, reason);
    }
}
