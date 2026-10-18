package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ShopShippingRouteForm {
    @NotBlank @Size(max = 32) private String catalogSource;
    @NotBlank @Size(max = 20) private String provinceCode;
    @NotBlank @Size(max = 20) private String wardCode;
    public String getCatalogSource() { return catalogSource; }
    public void setCatalogSource(String value) { catalogSource = value; }
    public String getProvinceCode() { return provinceCode; }
    public void setProvinceCode(String value) { provinceCode = value; }
    public String getWardCode() { return wardCode; }
    public void setWardCode(String value) { wardCode = value; }
}
