package com.utewedding.app.dto.response;

/** Versioned-by-shape application snapshot; persisted through EncryptedStringConverter. */
public record ShopApplicationSnapshot(
        String shopName,
        String businessType,
        String legalName,
        String taxCode,
        String registrationNumber,
        String contactEmail,
        String contactPhone,
        String businessAddress,
        String description) { }
