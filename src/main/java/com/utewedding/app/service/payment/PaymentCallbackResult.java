package com.utewedding.app.service.payment;

import com.utewedding.app.domain.PaymentCallbackOutcome;

public record PaymentCallbackResult(String code, String message, PaymentCallbackOutcome outcome) {
}
