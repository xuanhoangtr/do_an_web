package com.utewedding.app.service.web;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public interface WebCatalogService {
    List<WebViewModels.ProductView> featuredProducts();
    Page<WebViewModels.ProductView> products(String keyword, String category, int page, int size);
    default Page<WebViewModels.ProductView> products(String keyword, String category, int page, int size, String sort) {
        return products(keyword, category, page, size);
    }
    default Page<WebViewModels.ProductView> products(String keyword, String category, String material,
                                                      Long maxPrice, int page, int size, String sort) {
        return products(keyword, category, page, size, sort);
    }
    List<WebViewModels.ProductView> ranking(String section);
    Page<WebViewModels.ProductView> productsForAdmin(String keyword, String status, int page, int size);
    WebViewModels.ProductView product(UUID id);
    default List<WebViewModels.ProductView> productsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(this::product).filter(java.util.Objects::nonNull).toList();
    }
    default WebViewModels.ProductView productBySlugOrId(String idOrSlug) {
        return product(UUID.fromString(idOrSlug));
    }
    List<WebViewModels.ProductView> relatedProducts(UUID productId);
    List<WebViewModels.CategoryView> categories();
    List<String> materials();
}
