package com.utewedding.app.service;

import com.utewedding.app.dto.response.TopSellingProductResponse;
import java.time.Instant;
import java.util.List;

public interface CatalogRankingService {
    List<TopSellingProductResponse> topSelling(Instant from, Instant to, int limit);
}
