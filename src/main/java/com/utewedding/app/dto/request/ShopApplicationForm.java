package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Input for a vendor application. Legal fields are persisted only in an encrypted application snapshot. */
public class ShopApplicationForm {
    @NotBlank
    @Pattern(regexp = "INDIVIDUAL|HOUSEHOLD|COMPANY")
    private String businessType = "INDIVIDUAL";

    @NotBlank @Size(max = 160)
    private String shopName;

    @NotBlank @Size(max = 200)
    private String legalName;

    @Size(max = 60)
    private String taxCode;

    @Size(max = 80)
    private String registrationNumber;

    @NotBlank @Email @Size(max = 320)
    private String contactEmail;

    @NotBlank @Pattern(regexp = "^[0-9+() .-]{8,40}$")
    private String contactPhone;

    @NotBlank @Size(max = 500)
    private String businessAddress;

    @Size(max = 2000)
    private String description;

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String value) { businessType = value; }
    public String getShopName() { return shopName; }
    public void setShopName(String value) { shopName = value; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String value) { legalName = value; }
    public String getTaxCode() { return taxCode; }
    public void setTaxCode(String value) { taxCode = value; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String value) { registrationNumber = value; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String value) { contactEmail = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value; }
    public String getBusinessAddress() { return businessAddress; }
    public void setBusinessAddress(String value) { businessAddress = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
}
