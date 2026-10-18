package com.utewedding.app.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminShopShippingView(UUID shopId, String shopName, Map<String, ShopShippingAddressView> currentAddresses,
                                   List<AccountCard> accounts, List<BindingCard> bindings) {
    public AdminShopShippingView {
        currentAddresses = currentAddresses == null ? Map.of() : Map.copyOf(currentAddresses);
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }
    public record AccountCard(UUID id, String providerCode, String environment, String accountName, String status,
                              String credentialReferenceDisplay, String evidence, Instant verifiedAt) { }
    public record BindingCard(UUID id, int revision, UUID providerAccountId, String accountName,
                              String accountStatus, String externalShopIdMasked, String routeMode,
                              int pickupRevision,
                              int returnRevision, String status, boolean active, boolean routeCoverageVerified,
                              boolean codBeneficiaryVerified, String evidence, String codEvidence,
                              List<RouteCard> routes, Instant verifiedAt) {
        public BindingCard { routes = routes == null ? List.of() : List.copyOf(routes); }
    }
    public record RouteCard(UUID id, String catalogSource, String provinceCode, String provinceName,
                            String wardCode, String wardName, String status, boolean active,
                            String evidence, Instant verifiedAt) { }
}
