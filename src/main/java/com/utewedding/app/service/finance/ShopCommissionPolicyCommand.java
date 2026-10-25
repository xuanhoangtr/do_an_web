package com.utewedding.app.service.finance;

import java.time.Instant;

public record ShopCommissionPolicyCommand(int rateBps, Instant effectiveAt) { }
