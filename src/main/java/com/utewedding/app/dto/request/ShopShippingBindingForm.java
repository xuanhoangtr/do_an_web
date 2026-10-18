package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public class ShopShippingBindingForm {
    @NotNull private UUID providerAccountId;
    @NotBlank @Pattern(regexp = "^[0-9]{1,24}$") private String externalShopId;
    @NotBlank @Pattern(regexp = "^(LEGACY_ROUTES|API_QUOTE)$") private String routeMode = "LEGACY_ROUTES";
    public UUID getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(UUID value) { providerAccountId = value; }
    public String getExternalShopId() { return externalShopId; }
    public void setExternalShopId(String value) { externalShopId = value; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String value) { routeMode = value; }
}
