package com.utewedding.app.service.impl;

import com.utewedding.app.dto.response.TopSellingProductResponse;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.TopSellingRow;
import com.utewedding.app.service.CatalogRankingService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogRankingServiceImpl implements CatalogRankingService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ProductRepository productRepository;
    public CatalogRankingServiceImpl(ProductRepository productRepository) { this.productRepository = productRepository; }

    @Override
    @Transactional(readOnly = true)
    public List<TopSellingProductResponse> topSelling(Instant from, Instant to, int limit) {
        ZonedDateTime now = Instant.now().atZone(VIETNAM);
        Instant effectiveTo = to == null ? now.toInstant() : to;
        Instant effectiveFrom = from == null ? now.minusDays(30).toInstant() : from;
        int bounded = Math.min(Math.max(limit, 1), 100);
        return productRepository.findTopSelling(effectiveFrom, effectiveTo, PageRequest.of(0, bounded))
                .stream().map(this::toResponse).toList();
    }

    private TopSellingProductResponse toResponse(TopSellingRow row) {
        return new TopSellingProductResponse(row.getProductId(), row.getName(), row.getImageUrl(),
                row.getUnitPrice() == null ? 0 : row.getUnitPrice(),
                row.getAverageRating(), row.getReviewCount() == null ? 0 : row.getReviewCount(),
                row.getStockQuantity() == null ? 0 : row.getStockQuantity(),
                row.getSoldQuantity() == null ? 0 : row.getSoldQuantity(),
                row.getEffectivePrice() == null ? 0 : row.getEffectivePrice());
    }
}
