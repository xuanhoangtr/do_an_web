package com.utewedding.app.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
        @NotBlank @Size(max = 190) String providerTransactionId,
        @Size(max = 60) String provider,
        @Positive Long amount) {

    @JsonIgnore
    public PaymentRequest(String providerTransactionId, String provider) {
        this(providerTransactionId, provider, null);
    }
}
