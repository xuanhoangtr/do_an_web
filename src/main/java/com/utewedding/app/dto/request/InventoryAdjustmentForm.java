package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class InventoryAdjustmentForm {
    @NotNull(message = "Nhập số lượng cần điều chỉnh.")
    @Min(value = -1000000, message = "Mức điều chỉnh tối thiểu là -1.000.000.")
    @Max(value = 1000000, message = "Mức điều chỉnh tối đa là 1.000.000.")
    private Integer deltaQuantity;

    @NotNull(message = "Phiên bản tồn kho không hợp lệ. Tải lại trang rồi thử lại.")
    @Min(value = 0, message = "Phiên bản tồn kho không hợp lệ.")
    private Long version;

    @NotBlank(message = "Vui lòng ghi lý do điều chỉnh.")
    @Size(max = 500, message = "Lý do tối đa 500 ký tự.")
    private String reason;

    public Integer getDeltaQuantity() { return deltaQuantity; }
    public void setDeltaQuantity(Integer value) { deltaQuantity = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
