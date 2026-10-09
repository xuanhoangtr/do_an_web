package com.utewedding.app.controller;

import com.utewedding.app.service.web.WebCatalogService;
import com.utewedding.app.service.CatalogRankingService;
import com.utewedding.app.dto.response.TopSellingProductResponse;
import com.utewedding.app.service.web.WebViewModels;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductApiController {

    private final WebCatalogService catalogService;
    private final CatalogRankingService rankingService;

    @Autowired
    public ProductApiController(WebCatalogService catalogService, CatalogRankingService rankingService) {
        this.catalogService = catalogService;
        this.rankingService = rankingService;
    }

    @GetMapping("/top-selling")
    public List<TopSellingProductResponse> topSelling(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "8") int limit) {
        int boundedDays = Math.min(Math.max(days, 1), 90);
        return rankingService.topSelling(java.time.Instant.now().minusSeconds(boundedDays * 86400L),
                java.time.Instant.now(), limit);
    }

    @GetMapping
    public Page<WebViewModels.ProductView> list(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "") String section) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        if (section != null && !section.isBlank()) {
            List<WebViewModels.ProductView> ranked = catalogService.ranking(section);
            int from = Math.min(safePage * safeSize, ranked.size());
            int to = Math.min(from + safeSize, ranked.size());
            return new PageImpl<>(ranked.subList(from, to), PageRequest.of(safePage, safeSize), ranked.size());
        }
        return catalogService.products(keyword, category, safePage, safeSize, sort);
    }

    @GetMapping("/{productId}")
    public WebViewModels.ProductView get(@PathVariable UUID productId) {
        return catalogService.product(productId);
    }

    @GetMapping("/{productId}/related")
    public List<WebViewModels.ProductView> related(@PathVariable UUID productId) {
        return catalogService.relatedProducts(productId);
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return catalogService.categories().stream()
                .map(category -> new CategoryResponse(category.id(), category.name(), category.slug()))
                .toList();
    }

    public record CategoryResponse(Long id, String name, String slug) {
    }
}
