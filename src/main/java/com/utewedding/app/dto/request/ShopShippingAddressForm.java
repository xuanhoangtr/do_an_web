package com.utewedding.app.dto.request;

import com.utewedding.app.domain.ShopAddressKind;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ShopShippingAddressForm {
    @NotNull private ShopAddressKind addressKind;
    @NotBlank @Size(max = 32) private String catalogSource;
    @NotBlank @Size(max = 20) private String provinceCode;
    @NotBlank @Size(max = 20) private String wardCode;
    @NotBlank @Size(max = 500) private String addressLine;
    @NotBlank @Size(max = 160) private String contactName;
    @NotBlank @Pattern(regexp = "^[+0-9() .-]{7,30}$") private String contactPhone;
    @Min(0) private int expectedRevision;

    public ShopAddressKind getAddressKind() { return addressKind; }
    public void setAddressKind(ShopAddressKind value) { addressKind = value; }
    public String getCatalogSource() { return catalogSource; }
    public void setCatalogSource(String value) { catalogSource = value; }
    public String getProvinceCode() { return provinceCode; }
    public void setProvinceCode(String value) { provinceCode = value; }
    public String getWardCode() { return wardCode; }
    public void setWardCode(String value) { wardCode = value; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String value) { addressLine = value; }
    public String getContactName() { return contactName; }
    public void setContactName(String value) { contactName = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value; }
    public int getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(int value) { expectedRevision = value; }
}
