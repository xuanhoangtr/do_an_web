package com.utewedding.app.service.finance;

import com.utewedding.app.domain.PlatformFundReceiptEnvironment;
import com.utewedding.app.domain.PlatformFundReceiptSource;
import java.time.Instant;
import java.util.List;

public record PlatformFundReceiptCommand(
        PlatformFundReceiptSource sourceCode,
        String providerCode,
        String providerAccountReference,
        PlatformFundReceiptEnvironment environment,
        String sourceIdentity,
        long grossAmount,
        long feeAmount,
        long deductionAmount,
        long netAmount,
        String externalReference,
        String evidenceReference,
        String evidenceNote,
        Instant receivedAt,
        List<PlatformFundReceiptAllocationCommand> allocations) {
}
