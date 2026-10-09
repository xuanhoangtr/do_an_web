package com.utewedding.app.service;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductFavoriteService {
    FavoriteState get(UUID userId, UUID productId, RequestContext context);
    FavoriteState add(UUID userId, UUID productId, RequestContext context);
    FavoriteState remove(UUID userId, UUID productId, RequestContext context);
    Page<FavoriteProduct> list(UUID userId, Pageable pageable);

    record FavoriteState(UUID productId, boolean favorited, long count) { }
    record FavoriteProduct(UUID productId, String name, long unitPrice, String imageUrl,
                           int stockQuantity, String slug, String customizationGuide) {
        public FavoriteProduct(UUID productId, String name, long unitPrice, String imageUrl,
                               int stockQuantity, String slug) {
            this(productId, name, unitPrice, imageUrl, stockQuantity, slug, null);
        }

        public FavoriteProduct(UUID productId, String name, long unitPrice, String imageUrl, int stockQuantity) {
            this(productId, name, unitPrice, imageUrl, stockQuantity,
                    productId == null ? "" : productId.toString(), null);
        }

        public java.util.List<String> getPhotoSizeOptions() {
            return com.utewedding.app.util.ProductOptions.photoSizes(customizationGuide);
        }
    }
}
