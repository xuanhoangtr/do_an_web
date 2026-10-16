package com.utewedding.app.service.impl;

import com.utewedding.app.service.port.ShippingProvider;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ShippingProviderRegistry {
    private final Map<String, ShippingProvider> providers;
    public ShippingProviderRegistry(java.util.List<ShippingProvider> values) {
        providers = values.stream().collect(Collectors.toUnmodifiableMap(
                value -> value.code().toUpperCase(Locale.ROOT), Function.identity()));
    }
    public ShippingProvider require(String code) {
        ShippingProvider provider = code == null ? null : providers.get(code.toUpperCase(Locale.ROOT));
        if (provider == null) throw new IllegalArgumentException("Unknown shipping provider");
        return provider;
    }
    public boolean supports(String code) { return code != null && providers.containsKey(code.toUpperCase(Locale.ROOT)); }
}
