package com.utewedding.app.service.impl;

import com.utewedding.app.config.CloudinaryConfig;
import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.dto.response.PublicShopView;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ManagedMediaObjectRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShopRepository;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only public shop directory and detail data; private profile fields never leave this service. */
@Service
@Transactional(readOnly = true)
public class PublicShopService {
    private static final String LOGO = "SHOP_LOGO";
    private static final String BANNER = "SHOP_BANNER";

    private final ShopRepository shops;
    private final ProductRepository products;
    private final ManagedMediaObjectRepository media;
    private final CloudinaryConfig cloudinary;

    public PublicShopService(ShopRepository shops, ProductRepository products,
                             ManagedMediaObjectRepository media, CloudinaryConfig cloudinary) {
        this.shops = shops;
        this.products = products;
        this.media = media;
        this.cloudinary = cloudinary;
    }

    public Page<PublicShopView> directory(String keyword, int page, int size) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.length() > 120) query = query.substring(0, 120);
        PageRequest pageable = PageRequest.of(Math.min(Math.max(page, 0), 10_000),
                Math.min(Math.max(size, 1), 24), Sort.unsorted());
        Page<ShopRepository.PublicShopProjection> rows = shops.searchPublicShops(query, pageable);
        Map<UUID, ManagedMediaObjectEntity> publicMedia = loadPublicMedia(rows.getContent().stream()
                .flatMap(row -> java.util.stream.Stream.of(row.getLogoMediaId(), row.getBannerMediaId()))
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<UUID, String> logoUrls = new HashMap<>();
        Map<UUID, String> bannerUrls = new HashMap<>();
        rows.getContent().forEach(row -> {
            logoUrls.put(row.getId(), publicUrl(mediaById(publicMedia, row.getLogoMediaId()), row.getId(), LOGO));
            bannerUrls.put(row.getId(), publicUrl(mediaById(publicMedia, row.getBannerMediaId()), row.getId(), BANNER));
        });
        return rows.map(row -> view(row, logoUrls.get(row.getId()), bannerUrls.get(row.getId())));
    }

    public PublicShopView detail(String slug) {
        ShopEntity shop = shops.findBySlugIgnoreCaseAndKindAndStatus(slug, ShopKind.VENDOR, ShopStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        Set<UUID> mediaIds = new LinkedHashSet<>();
        if (shop.getLogoMediaId() != null) mediaIds.add(shop.getLogoMediaId());
        if (shop.getBannerMediaId() != null) mediaIds.add(shop.getBannerMediaId());
        Map<UUID, ManagedMediaObjectEntity> publicMedia = loadPublicMedia(mediaIds);
        return new PublicShopView(shop.getId(), shop.getSlug(), shop.getDisplayName(), shop.getDescription(),
                publicUrl(mediaById(publicMedia, shop.getLogoMediaId()), shop.getId(), LOGO),
                publicUrl(mediaById(publicMedia, shop.getBannerMediaId()), shop.getId(), BANNER),
                shop.getCreatedAt(), products.countByShopIdAndActiveTrue(shop.getId()));
    }

    private ManagedMediaObjectEntity mediaById(Map<UUID, ManagedMediaObjectEntity> candidates, UUID id) {
        return id == null ? null : candidates.get(id);
    }

    private Map<UUID, ManagedMediaObjectEntity> loadPublicMedia(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return media.findAllById(ids).stream()
                .filter(item -> item.getStatus() == ManagedMediaStatus.READY)
                .filter(item -> "upload".equals(item.getDeliveryType()))
                .filter(item -> LOGO.equals(item.getPurpose()) || BANNER.equals(item.getPurpose()))
                .collect(Collectors.toMap(ManagedMediaObjectEntity::getId, Function.identity(), (first, ignored) -> first));
    }

    private PublicShopView view(ShopRepository.PublicShopProjection row, String logoUrl, String bannerUrl) {
        return new PublicShopView(row.getId(), row.getSlug(), row.getDisplayName(), row.getDescription(),
                logoUrl, bannerUrl, row.getJoinedAt(), row.getActiveProductCount());
    }

    private String publicUrl(ManagedMediaObjectEntity item, UUID shopId, String expectedPurpose) {
        if (item == null || shopId == null || !shopId.equals(item.getShopId())
                || !expectedPurpose.equals(item.getPurpose())
                || item.getStatus() != ManagedMediaStatus.READY || !"upload".equals(item.getDeliveryType())) {
            return null;
        }
        String raw = item.getSecureUrl();
        if (raw == null || raw.length() > 2048) return null;
        try {
            URI uri = URI.create(raw);
            String cloudName = cloudinary.getCloudName();
            String path = uri.getRawPath();
            if ("https".equalsIgnoreCase(uri.getScheme()) && "res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && cloudName != null
                    && path != null && path.startsWith("/" + cloudName + "/image/upload/")) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // Omit malformed historical media URLs from public pages.
        }
        return null;
    }
}
