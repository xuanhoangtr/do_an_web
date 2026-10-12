package com.utewedding.app.service.impl;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.repository.ProductViewHistoryRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.web.WebCatalogService;
import com.utewedding.app.service.web.WebViewModels;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecentlyViewedProductService {
    public static final int MAX_ITEMS_PER_USER = 50;
    public static final int PAGE_SIZE = 12;
    public static final int DETAIL_SUGGESTION_LIMIT = 6;
    public static final Duration RETENTION = Duration.ofDays(90);
    private static final Set<UserRole> CUSTOMER_ROLES = Set.of(UserRole.USER, UserRole.CUSTOMER);

    private final ProductViewHistoryRepository history;
    private final UserRepository users;
    private final WebCatalogService catalog;

    public RecentlyViewedProductService(ProductViewHistoryRepository history, UserRepository users,
                                        WebCatalogService catalog) {
        this.history = history;
        this.users = users;
        this.catalog = catalog;
    }

    @Transactional
    public void record(UUID userId, UUID productId) {
        var user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE || !CUSTOMER_ROLES.contains(user.getRole())) {
            return;
        }
        Instant now = Instant.now();
        history.deleteExpiredForUser(userId, now.minus(RETENTION));
        history.upsert(userId, productId, now);
        history.pruneToFifty(userId);
    }

    @Transactional(readOnly = true)
    public Page<WebViewModels.ProductView> list(UUID userId, int page) {
        int pageNumber = Math.max(0, page);
        PageRequest pageable = PageRequest.of(pageNumber, PAGE_SIZE);
        Page<UUID> ids = history.findRecentActiveProductIds(userId,
                Instant.now().minus(RETENTION), pageable);
        List<WebViewModels.ProductView> products = catalog.productsByIds(ids.getContent());
        return new PageImpl<>(products, pageable, ids.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<WebViewModels.ProductView> recommendations(UUID userId, UUID currentProductId) {
        List<UUID> ids = history.findRecentActiveProductIdsExcluding(userId, currentProductId,
                Instant.now().minus(RETENTION), PageRequest.of(0, DETAIL_SUGGESTION_LIMIT));
        return catalog.productsByIds(ids);
    }

    @Transactional
    public void remove(UUID userId, UUID productId) {
        if (!isActiveCustomer(userId)) return;
        history.deleteById(new com.utewedding.app.domain.entity.ProductViewHistoryId(userId, productId));
    }

    @Transactional
    public void clear(UUID userId) {
        if (!isActiveCustomer(userId)) return;
        history.deleteByUserId(userId);
    }

    @Transactional
    public int cleanupExpired() {
        return history.deleteExpiredBefore(Instant.now().minus(RETENTION));
    }

    private boolean isActiveCustomer(UUID userId) {
        var user = users.findByIdForUpdate(userId).orElse(null);
        return user != null && user.getStatus() == UserStatus.ACTIVE
                && CUSTOMER_ROLES.contains(user.getRole());
    }
}
