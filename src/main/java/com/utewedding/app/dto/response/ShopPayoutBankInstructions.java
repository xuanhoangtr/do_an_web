package com.utewedding.app.dto.response;

import java.util.UUID;

/** Sensitive, audited view used only by active finance administrators. */
public record ShopPayoutBankInstructions(UUID destinationId, long destinationVersion,
                                         String bankCode, String accountNumber, String accountHolderName) { }
