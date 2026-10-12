package com.utewedding.app.controller;

import com.utewedding.app.service.web.WebCatalogService;
import com.utewedding.app.service.ReviewService;
import java.util.UUID;
import java.util.List;
import com.utewedding.app.dto.request.CreateProductReviewRequest;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.web.CurrentUserContext;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@Controller
public class ProductWebController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductWebController.class);

    private final WebCatalogService catalogService;
    private final ReviewService reviewService;
    private final CurrentUserContext currentUser;
    private final com.utewedding.app.service.impl.RecentlyViewedProductService recentlyViewed;

    public ProductWebController(WebCatalogService catalogService, ReviewService reviewService,
                               CurrentUserContext currentUser) {
        this(catalogService, reviewService, currentUser, null);
    }

    @Autowired
    public ProductWebController(WebCatalogService catalogService, ReviewService reviewService,
                                CurrentUserContext currentUser,
                                com.utewedding.app.service.impl.RecentlyViewedProductService recentlyViewed) {
        this.catalogService = catalogService;
        this.reviewService = reviewService;
        this.currentUser = currentUser;
        this.recentlyViewed = recentlyViewed;
    }

    @GetMapping({"/", "/home"})
    public String home(Model model) {
        model.addAttribute("featuredProducts", catalogService.featuredProducts());
        model.addAttribute("categories", catalogService.categories());
        model.addAttribute("newProducts", catalogService.ranking("newest"));
        model.addAttribute("bestSellingProducts", catalogService.ranking("best_selling"));
        model.addAttribute("topRatedProducts", catalogService.ranking("top_rated"));
        model.addAttribute("favoriteProducts", catalogService.ranking("favorites"));
        model.addAttribute("pageTitle", "UTEWedding — Mỗi kỷ niệm, một câu chuyện");
        return "shop/index";
    }

    @GetMapping("/products")
    public String products(@RequestParam(defaultValue = "") String keyword,
                           @RequestParam(defaultValue = "") String category,
                           @RequestParam(defaultValue = "") String material,
                           @RequestParam(required = false) Long maxPrice,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "newest") String sort,
                           Model model) {
        model.addAttribute("products", catalogService.products(
                keyword, category, material, maxPrice, page, 24, sort));
        model.addAttribute("categories", catalogService.categories());
        model.addAttribute("currentCategory", category);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("keyword", keyword);
        model.addAttribute("materials", catalogService.materials());
        model.addAttribute("selectedMaterial", material);
        model.addAttribute("maxPrice", maxPrice);
        model.addAttribute("selectedSort", sort);
        return "shop/catalog";
    }

    @GetMapping("/products/fragment")
    public String productFragment(@RequestParam(defaultValue = "") String keyword,
                                  @RequestParam(defaultValue = "") String category,
                                  @RequestParam(defaultValue = "") String material,
                                  @RequestParam(required = false) Long maxPrice,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "24") int size,
                                  @RequestParam(defaultValue = "newest") String sort,
                                  Model model, HttpServletResponse response) {
        var products = catalogService.products(keyword, category, material, maxPrice,
                Math.max(0, page), Math.min(Math.max(1, size), 100), sort);
        response.setHeader("X-Has-Next", Boolean.toString(products.hasNext()));
        model.addAttribute("products", products);
        return "shop/product-grid :: grid";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable("id") String idOrSlug, Authentication authentication,
                         Model model, HttpServletResponse response) {
        var product = catalogService.productBySlugOrId(idOrSlug);
        UUID productId = product.getId();
        model.addAttribute("product", product);
        model.addAttribute("relatedProducts", catalogService.relatedProducts(productId));
        model.addAttribute("reviews", reviewService == null ? java.util.List.of() : reviewService.list(productId, 0, 20).getContent());
        model.addAttribute("reviewForm", new com.utewedding.app.service.web.WebViewModels.ReviewForm());
        UUID viewerId = null;
        boolean customer = isCustomer(authentication);
        if (customer) viewerId = currentUser.requireUserId(authentication);
        model.addAttribute("reviewEligibility", reviewService == null
                ? com.utewedding.app.dto.response.ReviewEligibility.denied("Đăng nhập để đánh giá.")
                : reviewService.eligibility(viewerId, productId));
        model.addAttribute("recentlyViewedProducts", List.of());
        if (customer && recentlyViewed != null) {
            response.setHeader("Cache-Control", "private, no-store");
            try {
                recentlyViewed.record(viewerId, productId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Recently viewed history write failed: {}", exception.getClass().getSimpleName());
            }
            try {
                model.addAttribute("recentlyViewedProducts", recentlyViewed.recommendations(viewerId, productId));
            } catch (RuntimeException exception) {
                LOGGER.warn("Recently viewed recommendations unavailable: {}", exception.getClass().getSimpleName());
            }
        }
        return "shop/detail";
    }

    private boolean isCustomer(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority ->
                        "ROLE_USER".equals(authority.getAuthority())
                                || "ROLE_CUSTOMER".equals(authority.getAuthority()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/products/reviews/{reviewId}/report")
    @PreAuthorize("isAuthenticated()")
    public String reportReview(@PathVariable UUID reviewId, @RequestParam String reason,
                               Authentication authentication, RedirectAttributes redirectAttributes,
                               @RequestParam(required = false) UUID productId) {
        try {
            reviewService.report(currentUser.requireUserId(authentication), reviewId,
                    new com.utewedding.app.dto.request.ReportProductReviewRequest(reason),
                    new RequestContext(null, null, null));
            redirectAttributes.addFlashAttribute("reviewMessage", "Đã gửi báo cáo đánh giá.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("reviewMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/products/" + redirectKey(productId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/products/{id}/reviews")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String createReview(@PathVariable UUID id, Authentication authentication,
                               @Valid @org.springframework.web.bind.annotation.ModelAttribute CreateProductReviewRequest request,
                               BindingResult result,
                               @RequestPart(value = "media", required = false) MultipartFile[] media,
                               RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("reviewMessage", "Đánh giá cần có từ 50 đến 2000 ký tự và số sao từ 1 đến 5.");
        } else {
            var userId = currentUser.requireUserId(authentication);
            var created = reviewService.create(userId, id, request,
                    new RequestContext(null, null, null));
            if (media != null && media.length > 0) {
                reviewService.uploadMedia(userId, created.id(), media);
            }
            redirectAttributes.addFlashAttribute("reviewMessage", "Đánh giá đã được đăng.");
        }
        return "redirect:/products/" + redirectKey(id);
    }

    private String redirectKey(UUID productId) {
        if (productId == null) {
            return "";
        }
        try {
            var product = catalogService.product(productId);
            if (product != null && product.getSlug() != null && !product.getSlug().isBlank()) {
                return product.getSlug();
            }
        } catch (RuntimeException ignored) {
        }
        return productId.toString();
    }
}
