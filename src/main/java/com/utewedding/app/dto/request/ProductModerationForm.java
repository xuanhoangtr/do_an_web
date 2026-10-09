package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Size;

public class ProductModerationForm {
    @Size(max = 1000, message = "Lý do tối đa 1000 ký tự.")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
