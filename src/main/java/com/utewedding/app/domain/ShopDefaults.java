package com.utewedding.app.domain;

import java.util.UUID;

/** Stable identity used by the platform-owned catalog and historical records. */
public final class ShopDefaults {
    public static final UUID PLATFORM_SHOP_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final String PLATFORM_SHOP_NAME = "UTEWedding";

    private ShopDefaults() { }
}
