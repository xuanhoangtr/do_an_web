package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Editable public-facing shop profile; media URLs and shop identifiers are never form fields. */
public class VendorShopProfileForm {
    @NotBlank(message = "Tên cửa hàng là bắt buộc.")
    @Size(max = 160, message = "Tên cửa hàng tối đa 160 ký tự.")
    private String shopName;

    @Size(max = 2000, message = "Mô tả tối đa 2.000 ký tự.")
    private String description;

    @Email(message = "Email liên hệ không hợp lệ.")
    @Size(max = 320, message = "Email liên hệ tối đa 320 ký tự.")
    private String contactEmail;

    @Pattern(regexp = "^$|^[+0-9() .-]{7,30}$", message = "Số điện thoại liên hệ không hợp lệ.")
    private String contactPhone;

    @Min(value = 0, message = "Phiên bản hồ sơ không hợp lệ.")
    private long version;

    public String getShopName() { return shopName; }
    public void setShopName(String value) { shopName = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String value) { contactEmail = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { version = value; }
}
