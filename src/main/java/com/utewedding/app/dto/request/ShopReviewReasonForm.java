package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Review/suspension explanation; service validation also checks normalized length. */
public class ShopReviewReasonForm {
    @NotBlank @Size(min = 10, max = 1000)
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
