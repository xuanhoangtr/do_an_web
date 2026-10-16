package com.utewedding.app.service.port;

import java.util.List;

/** Parcel facts snapshotted at checkout and reused when a carrier order is created. */
public record ShippingParcel(int weightGrams, int lengthCm, int widthCm, int heightCm,
                             String content, List<Item> items) {
    public ShippingParcel {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(String name, String code, int quantity, long price,
                       int weightGrams, int lengthCm, int widthCm, int heightCm) { }
}
