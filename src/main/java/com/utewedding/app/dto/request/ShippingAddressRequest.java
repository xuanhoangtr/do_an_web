package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Address submitted at checkout; values are copied into the order snapshot. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShippingAddressRequest(
        @NotBlank @Size(max = 160) String recipientName,
        @NotBlank @Pattern(regexp = "^[0-9+() .-]{8,40}$", message = "Số điện thoại không hợp lệ.")
        String phone,
        @NotBlank @Size(max = 500) String addressLine,
        @NotBlank @Size(max = 20) String provinceCode,
        @NotBlank @Size(max = 20) String wardCode) {

    /** Compatibility constructor for old internal callers; production checkout requires codes. */
    public ShippingAddressRequest(String recipientName, String phone, String addressLine,
                                  String ward, String district, String province) {
        this(recipientName, phone, addressLine, null, null);
    }

    public String ward() { return wardCode; }
    public String district() { return null; }
    public String province() { return provinceCode; }
}
