package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ShippingProviderAccountForm {
    @NotBlank @Size(max = 120) private String accountName;
    @NotBlank @Pattern(regexp = "^credref:[A-Za-z0-9][A-Za-z0-9._/-]{2,180}$")
    private String credentialReference;
    public String getAccountName() { return accountName; }
    public void setAccountName(String value) { accountName = value; }
    public String getCredentialReference() { return credentialReference; }
    public void setCredentialReference(String value) { credentialReference = value; }
}
