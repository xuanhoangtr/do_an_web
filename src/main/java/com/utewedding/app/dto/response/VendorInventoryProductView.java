package com.utewedding.app.dto.response;

import java.util.UUID;

public record VendorInventoryProductView(UUID productId, String name, String sku,
                                         int stockQuantity, long version) { }
