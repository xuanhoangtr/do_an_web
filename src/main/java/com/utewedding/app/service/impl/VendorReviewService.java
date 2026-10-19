package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ProductReviewStatus;
import com.utewedding.app.domain.entity.ProductReviewEntity;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ProductReviewRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.security.UserPrincipal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only view of approved reviews for products in the authenticated owner's shop. */
@Service
public class VendorReviewService {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(DISPLAY_ZONE);

    private final VendorWorkspaceContext workspace;
    private final ProductReviewRepository reviews;
    private final ProductRepository products;
    private final UserRepository users;

    public VendorReviewService(VendorWorkspaceContext workspace, ProductReviewRepository reviews,
                               ProductRepository products, UserRepository users) {
        this.workspace = workspace;
        this.reviews = reviews;
        this.products = products;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<ReviewRow> list(UserPrincipal actor, int requestedPage) {
        UUID shopId = workspace.requireWorkspace(actor).shopId();
        Page<ProductReviewEntity> page = reviews.findForVendorShop(shopId, ProductReviewStatus.APPROVED,
                PageRequest.of(Math.max(0, requestedPage), 12));
        Map<UUID, String> productNames = new HashMap<>();
        products.findAllById(page.getContent().stream().map(ProductReviewEntity::getProductId).distinct().toList())
                .forEach(product -> productNames.put(product.getId(), product.getName()));
        Map<UUID, String> reviewerNames = new HashMap<>();
        users.findAllById(page.getContent().stream().map(ProductReviewEntity::getUserId).distinct().toList())
                .forEach(user -> reviewerNames.put(user.getId(), user.getDisplayName()));
        return page.map(review -> new ReviewRow(review.getId(),
                productNames.getOrDefault(review.getProductId(), "Sản phẩm đã ẩn"),
                reviewerNames.getOrDefault(review.getUserId(), "Khách hàng"),
                review.getRating(), review.getContent(), DISPLAY_FORMAT.format(review.getCreatedAt())));
    }

    public record ReviewRow(UUID id, String productName, String reviewerName, int rating,
                            String content, String createdAtLocal) { }
}
