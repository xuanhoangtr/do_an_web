package com.utewedding.app.dto.request;

import java.util.UUID;

public record ShopPayoutMarkPaidCommand(String providerReference, UUID proofMediaId, String note) { }
