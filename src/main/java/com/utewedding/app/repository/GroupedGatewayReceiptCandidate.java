package com.utewedding.app.repository;

import com.utewedding.app.domain.PaymentProviderEnvironment;
import java.time.Instant;
import java.util.UUID;

/** One canonical group capture awaiting evidence that funds reached the platform. */
public record GroupedGatewayReceiptCandidate(UUID captureTransactionId, UUID groupId,
                                            String groupNumber, String provider,
                                            String providerTransactionId, long amount,
                                            Instant capturedAt, PaymentProviderEnvironment providerEnvironment,
                                            long allocationCount) { }
