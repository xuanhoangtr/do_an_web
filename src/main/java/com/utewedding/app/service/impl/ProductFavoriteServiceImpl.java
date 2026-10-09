package com.utewedding.app.service.impl;

import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ProductFavoriteRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.service.ProductFavoriteService;
import com.utewedding.app.service.ProductFavoriteService.FavoriteProduct;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductFavoriteServiceImpl implements ProductFavoriteService {
    private final ProductRepository productRepository;
    private final ProductFavoriteRepository favoriteRepository;
    private final AuditLogPort auditLog;

    public ProductFavoriteServiceImpl(ProductRepository productRepository,
                                      ProductFavoriteRepository favoriteRepository,
                                      AuditLogPort auditLog) {
        this.productRepository = productRepository;
        this.favoriteRepository = favoriteRepository;
        this.auditLog = auditLog;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FavoriteProduct> list(UUID userId, org.springframework.data.domain.Pageable pageable) {
        return favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(favorite -> {
            var product = productRepository.findById(favorite.getProductId()).orElse(null);
            if (product == null) {
                return new FavoriteProduct(favorite.getProductId(), "Sản phẩm không còn", 0, null, 0,
                        favorite.getProductId() == null ? "" : favorite.getProductId().toString());
            }
            String slug = product.getSlug() != null && !product.getSlug().isBlank()
                    ? product.getSlug() : product.getId().toString();
            return new FavoriteProduct(product.getId(), product.getName(), product.getUnitPrice(),
                    product.getImageUrl(), product.getStockQuantity(), slug,
                    product.getCustomizationGuide());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public FavoriteState get(UUID userId, UUID productId, RequestContext context) {
        requireActiveProduct(productId);
        return state(userId, productId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FavoriteState add(UUID userId, UUID productId, RequestContext context) {
        requireActiveProduct(productId);
        if (favoriteRepository.insertIfAbsent(userId, productId) > 0) {
            auditLog.record("FAVORITE_ADDED", userId, "Product", productId, Map.of(), context);
        }
        return state(userId, productId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FavoriteState remove(UUID userId, UUID productId, RequestContext context) {
        requireActiveProduct(productId);
        long removed = favoriteRepository.deleteByUserIdAndProductId(userId, productId);
        if (removed > 0) {
            auditLog.record("FAVORITE_REMOVED", userId, "Product", productId, Map.of(), context);
        }
        return state(userId, productId);
    }

    private void requireActiveProduct(UUID productId) {
        if (productRepository.findByIdAndActiveTrue(productId).isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy sản phẩm.");
        }
    }

    private FavoriteState state(UUID userId, UUID productId) {
        return new FavoriteState(productId,
                favoriteRepository.existsByUserIdAndProductId(userId, productId),
                favoriteRepository.countByProductId(productId));
    }
}
