package com.utewedding.app.dto.response;

import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.VoucherUsageSlot;

/** Customer-safe display snapshot for one applied voucher slot. */
public record AppliedVoucherResponse(VoucherUsageSlot slot, String code, VoucherType type,
                                     long discountAmount) {
}
