package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Size;

/** Reactivation reason is optional; unlike rejection and suspension it is not a blocking decision. */
public class ShopReactivationForm {
    @Size(max = 1000)
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
