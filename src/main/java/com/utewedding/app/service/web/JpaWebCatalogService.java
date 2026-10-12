package com.utewedding.app.service.web;

import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.CategoryEntity;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CatalogCategoryRepository;
import com.utewedding.app.repository.ProductImageRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ProductFavoriteRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class JpaWebCatalogService implements WebCatalogService {

    private final ProductRepository productRepository;
    private final CatalogCategoryRepository categoryRepository;
    private final ProductImageRepository imageRepository;
    private final ProductFavoriteRepository favoriteRepository;

    @Autowired
    public JpaWebCatalogService(ProductRepository productRepository,
                                CatalogCategoryRepository categoryRepository,
                                ProductImageRepository imageRepository,
                                ProductFavoriteRepository favoriteRepository) {
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository");
        this.imageRepository = Objects.requireNonNull(imageRepository, "imageRepository");
        this.favoriteRepository = Objects.requireNonNull(favoriteRepository, "favoriteRepository");
    }

    @Override
    public java.util.List<WebViewModels.ProductView> featuredProducts() {
        return toViews(productRepository.findByActiveTrueOrderByFeaturedDescCreatedAtDesc(
                PageRequest.of(0, 12))).getContent();
    }

    @Override
    public Page<WebViewModels.ProductView> products(String keyword, String category, int page, int size) {
        return products(keyword, category, page, size, "newest");
    }

    @Override
    public Page<WebViewModels.ProductView> products(String keyword, String category, int page, int size, String sort) {
        return products(keyword, category, null, null, page, size, sort);
    }

    @Override
    public Page<WebViewModels.ProductView> products(String keyword, String category, String material,
                                                     Long maxPrice, int page, int size, String sort) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String selectedMaterial = material == null || material.isBlank() ? null : material;
        Long categoryId = resolveCategoryId(category);
        String normalizedSort = sort == null ? "newest" : sort.trim().toLowerCase();
        Pageable pageable;
        if ("popular".equals(normalizedSort)) {
            pageable = PageRequest.of(Math.max(page, 0), boundedSize(size));
            return toViews(productRepository.searchActiveByPopularity(normalizedKeyword, categoryId,
                    selectedMaterial, maxPrice, pageable));
        }
        if ("price_asc".equals(normalizedSort) || "price_desc".equals(normalizedSort)) {
            pageable = PageRequest.of(Math.max(page, 0), boundedSize(size));
            Page<ProductEntity> sorted = "price_asc".equals(normalizedSort)
                    ? productRepository.searchActiveByEffectivePriceAsc(normalizedKeyword, categoryId,
                            selectedMaterial, maxPrice, pageable)
                    : productRepository.searchActiveByEffectivePriceDesc(normalizedKeyword, categoryId,
                            selectedMaterial, maxPrice, pageable);
            return toViews(sorted);
        }
        Sort sortOrder = switch (normalizedSort) {
            case "rating", "top_rated" -> Sort.by(Sort.Direction.DESC, "averageRating")
                    .and(Sort.by(Sort.Direction.DESC, "reviewCount"));
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        pageable = PageRequest.of(Math.max(page, 0), boundedSize(size), sortOrder);
        return toViews(productRepository.searchActive(normalizedKeyword, categoryId,
                selectedMaterial, maxPrice, pageable));
    }

    @Override
    public List<WebViewModels.ProductView> ranking(String section) {
        String value = section == null ? "newest" : section.trim().toLowerCase();
        Page<ProductEntity> page;
        if ("best_selling".equals(value)) {
            return toViews(new PageImpl<>(productRepository.findTopBestSelling())).getContent();
        }
        if ("favorites".equals(value)) {
            List<UUID> ids = favoriteRepository.findTopProductIds();
            Map<UUID, ProductEntity> byId = productRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
            return toViews(new PageImpl<>(ids.stream().map(byId::get).filter(Objects::nonNull).toList())).getContent();
        }
        page = "top_rated".equals(value)
                ? productRepository.findByActiveTrueOrderByAverageRatingDescReviewCountDesc(PageRequest.of(0, 20))
                : productRepository.findByActiveTrueOrderByCreatedAtDesc(PageRequest.of(0, 20));
        return toViews(page).getContent();
    }

    @Override
    public Page<WebViewModels.ProductView> productsForAdmin(String keyword, String status,
                                                            int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedStatus = status == null ? "all" : status.trim().toLowerCase();
        Boolean active = switch (normalizedStatus) {
            case "active" -> true;
            case "hidden" -> false;
            default -> null;
        };
        boolean outOfStock = "out".equals(normalizedStatus);
        Pageable pageable = PageRequest.of(Math.max(page, 0), boundedSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return toViews(productRepository.searchForAdmin(normalizedKeyword, active, outOfStock, pageable));
    }

    @Override
    public WebViewModels.ProductView product(UUID id) {
        ProductEntity entity = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
        return toViews(new PageImpl<>(java.util.List.of(entity))).getContent().get(0);
    }

    @Override
    public WebViewModels.ProductView productBySlugOrId(String idOrSlug) {
        if (idOrSlug == null || idOrSlug.isBlank()) {
            throw new ResourceNotFoundException("Không tìm thấy sản phẩm.");
        }
        String normalized = idOrSlug.trim();
        try {
            return product(UUID.fromString(normalized));
        } catch (IllegalArgumentException ignored) {
            // Not a UUID; resolve by product slug.
        }
        ProductEntity entity = productRepository.findBySlugIgnoreCaseAndActiveTrue(normalized)
                .or(() -> normalized.startsWith("seed-")
                        ? productRepository.findBySlugIgnoreCaseAndActiveTrue(normalized.substring(5))
                        : productRepository.findBySlugIgnoreCaseAndActiveTrue("seed-" + normalized))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
        return toViews(new PageImpl<>(java.util.List.of(entity))).getContent().get(0);
    }

    @Override
    public List<WebViewModels.ProductView> productsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<UUID, WebViewModels.ProductView> byId = toViews(new PageImpl<>(
                productRepository.findByIdInAndActiveTrue(ids))).getContent().stream()
                .collect(Collectors.toMap(WebViewModels.ProductView::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Override
    public java.util.List<WebViewModels.ProductView> relatedProducts(UUID productId) {
        ProductEntity source = productRepository.findByIdAndActiveTrue(productId).orElse(null);
        if (source == null) {
            return java.util.List.of();
        }
        Page<ProductEntity> related = source.getCategoryId() == null
                ? productRepository.findByActiveTrueAndIdNotOrderByCreatedAtDesc(
                        productId, PageRequest.of(0, 4))
                : productRepository.findByActiveTrueAndCategoryIdAndIdNotOrderByCreatedAtDesc(
                        source.getCategoryId(), productId, PageRequest.of(0, 4));
        return toViews(related).getContent();
    }

    @Override
    public java.util.List<WebViewModels.CategoryView> categories() {
        return categoryRepository.findByActiveTrueOrderByDisplayOrderAscNameAsc().stream()
                .map(category -> new WebViewModels.CategoryView(category.getId(), category.getName(),
                        category.getSlug(), category.getDescription()))
                .toList();
    }

    @Override
    public java.util.List<String> materials() {
        return productRepository.findActiveMaterials();
    }

    private Page<WebViewModels.ProductView> toViews(Page<ProductEntity> products) {
        var categoryIds = products.getContent().stream()
                .map(ProductEntity::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, CategoryEntity> categories = categoryIds.isEmpty()
                ? Map.of()
                : categoryRepository.findAllById(categoryIds).stream()
                        .collect(Collectors.toMap(CategoryEntity::getId, Function.identity()));

        Map<UUID, java.util.List<String>> images = products.getContent().isEmpty()
                ? Map.of()
                : imageRepository.findByProductIdInOrderByProductIdAscDisplayOrderAscCreatedAtAsc(
                        products.getContent().stream().map(ProductEntity::getId).toList())
                        .stream().collect(Collectors.groupingBy(
                                com.utewedding.app.domain.entity.ProductImageEntity::getProductId,
                                java.util.LinkedHashMap::new,
                                Collectors.mapping(
                                        com.utewedding.app.domain.entity.ProductImageEntity::getImageUrl,
                                        Collectors.toList())));

        return products.map(product -> toView(product, categories,
                images.getOrDefault(product.getId(), java.util.List.of())));
    }

    private WebViewModels.ProductView toView(ProductEntity product,
                                             Map<Long, CategoryEntity> categories,
                                             java.util.List<String> galleryImages) {
        CategoryEntity category = categories.get(product.getCategoryId());
        String categoryName = category == null ? "Kỷ vật cưới" : category.getName();
        String shopName = "UTEWedding";
        String imageUrl = galleryImages.isEmpty() ? product.getImageUrl() : galleryImages.get(0);
        long price = product.getUnitPrice();
        long currentUnitPrice = price - Math.multiplyExact(price, product.getDiscountPercent()) / 100;
        BigDecimal originalPrice = product.getDiscountPercent() > 0
                ? BigDecimal.valueOf(price) : product.getCompareAtPrice();
        return new WebViewModels.ProductView(product.getId(), product.getSlug(), product.getName(), categoryName,
                imageUrl, galleryImages, BigDecimal.valueOf(currentUnitPrice),
                originalPrice, product.getDiscountPercent(), product.getAverageRating(), product.getReviewCount(),
                product.getDescription(), product.getMaterial(), product.getStockQuantity(),
                product.isActive(), shopName, product.getMinOrderQuantity(), product.isCustomizable(),
                product.getCustomizationGuide(), product.getSku());
    }

    private Long resolveCategoryId(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String value = category.trim();
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return categoryRepository.findBySlugIgnoreCase(value)
                    .or(() -> categoryRepository.findByNameIgnoreCase(value))
                    .map(CategoryEntity::getId)
                    .orElse(-1L);
        }
    }

    private int boundedSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
