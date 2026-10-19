package com.utewedding.app.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Safe vendor profile view. Private evidence contains metadata only, never a provider URL. */
public record VendorShopProfileView(
        String shopCode,
        String shopName,
        String description,
        String contactEmail,
        String contactPhone,
        String logoUrl,
        String bannerUrl,
        long version,
        String latestApplicationStatus,
        List<EvidenceFile> evidence) {

    public VendorShopProfileView {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public record EvidenceFile(UUID id, long bytes, String mimeType, Instant createdAt) { }
}
