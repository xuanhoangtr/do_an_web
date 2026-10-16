package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SavedAddressForm {
    @Size(max = 60) private String label;
    @NotBlank @Size(max = 160) private String recipientName;
    @NotBlank @Pattern(regexp = "^[0-9+() .-]{8,40}$") private String phone;
    @NotBlank @Size(max = 500) private String addressLine;
    @jakarta.validation.constraints.NotBlank(message = "Vui lòng chọn tỉnh/thành.")
    @Size(max = 20) private String provinceCode;
    @jakarta.validation.constraints.NotBlank(message = "Vui lòng chọn phường/xã.")
    @Size(max = 20) private String wardCode;
    private boolean defaultAddress;
    public String getLabel() { return label; }
    public void setLabel(String value) { label = value; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String value) { recipientName = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String value) { addressLine = value; }
    public String getWardCode() { return wardCode; }
    public void setWardCode(String value) { wardCode = value; }
    public String getProvinceCode() { return provinceCode; }
    public void setProvinceCode(String value) { provinceCode = value; }
    public boolean isDefaultAddress() { return defaultAddress; }
    public void setDefaultAddress(boolean value) { defaultAddress = value; }

    public SavedAddressRequest toRequest() {
        return new SavedAddressRequest(label,
                new ShippingAddressRequest(recipientName, phone, addressLine, provinceCode, wardCode),
                defaultAddress);
    }
}
