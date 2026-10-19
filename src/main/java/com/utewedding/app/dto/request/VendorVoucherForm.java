package com.utewedding.app.dto.request;

import com.utewedding.app.domain.VoucherType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Owner-editable voucher terms. Scope and funding are intentionally absent. */
public record VendorVoucherForm(
        @NotBlank(message = "Mã voucher không được để trống.")
        @Size(min = 3, max = 80, message = "Mã voucher phải từ 3 đến 80 ký tự.")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Mã voucher chỉ được dùng chữ, số, gạch ngang và gạch dưới.")
        String code,
        @NotNull(message = "Chọn loại giảm giá.") VoucherType type,
        @Min(value = 1, message = "Giá trị giảm phải lớn hơn 0.") long valueAmount,
        @Min(value = 0, message = "Đơn tối thiểu không được âm.") long minimumOrderAmount,
        @Min(value = 0, message = "Mức giảm tối đa không được âm.") Long maximumDiscountAmount,
        @NotBlank(message = "Nhập thời điểm bắt đầu.")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$", message = "Thời điểm bắt đầu không hợp lệ.")
        String startsAt,
        @NotBlank(message = "Nhập thời điểm kết thúc.")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$", message = "Thời điểm kết thúc không hợp lệ.")
        String endsAt,
        @Min(value = 0, message = "Tổng lượt dùng không được âm.") Integer totalQuota,
        @Min(value = 0, message = "Lượt dùng mỗi người không được âm.") Integer perUserQuota,
        @NotNull(message = "Chọn trạng thái voucher.") Boolean active) {

    @AssertTrue(message = "Voucher phần trăm không được vượt quá 100%.")
    public boolean isPercentageAmountValid() {
        return type != VoucherType.PERCENT || valueAmount <= 100;
    }
}
