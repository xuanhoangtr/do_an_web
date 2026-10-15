package com.utewedding.app.domain;

public enum PaymentReconciliationResolution {
    NONE,
    AUTO_CAPTURED,
    AUTO_REFUND_SUCCEEDED,
    AUTO_REFUND_FAILED,
    OPEN
}
