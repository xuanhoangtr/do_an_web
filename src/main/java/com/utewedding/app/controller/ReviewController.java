package com.utewedding.app.controller;

import com.utewedding.app.dto.request.CreateProductReviewRequest;
import com.utewedding.app.dto.request.ModerateProductReviewRequest;
import com.utewedding.app.dto.request.ReportProductReviewRequest;
import com.utewedding.app.dto.request.UpdateProductReviewRequest;
import com.utewedding.app.dto.response.MessageResponse;
import com.utewedding.app.dto.response.ProductReviewMediaResponse;
import com.utewedding.app.dto.response.ProductReviewResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewService reviewService;
    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/products/{productId}/reviews")
    public Page<ProductReviewResponse> list(@PathVariable UUID productId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return reviewService.list(productId, page, size);
    }

    @PostMapping("/products/{productId}/reviews")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public ProductReviewResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable UUID productId,
                                        @Valid @RequestBody CreateProductReviewRequest request,
                                        HttpServletRequest httpRequest) {
        return reviewService.create(principal.getId(), productId, request, context(httpRequest));
    }

    @PatchMapping("/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public ProductReviewResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable UUID reviewId,
                                        @Valid @RequestBody UpdateProductReviewRequest request,
                                        HttpServletRequest httpRequest) {
        return reviewService.update(principal.getId(), reviewId, request, context(httpRequest));
    }

    @DeleteMapping("/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public MessageResponse delete(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable UUID reviewId, HttpServletRequest httpRequest) {
        return reviewService.delete(principal.getId(), reviewId, context(httpRequest));
    }

    @PostMapping("/reviews/{reviewId}/reports")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public MessageResponse report(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable UUID reviewId,
                                  @Valid @RequestBody ReportProductReviewRequest request,
                                  HttpServletRequest httpRequest) {
        return reviewService.report(principal.getId(), reviewId, request, context(httpRequest));
    }

    @PatchMapping("/admin/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public ProductReviewResponse moderate(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable UUID reviewId,
                                          @Valid @RequestBody ModerateProductReviewRequest request,
                                          HttpServletRequest httpRequest) {
        return reviewService.moderate(principal.getId(), reviewId, request, context(httpRequest));
    }

    @PostMapping(value = "/reviews/{reviewId}/media",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public List<ProductReviewMediaResponse> uploadMedia(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID reviewId,
            @RequestPart("files") MultipartFile[] files) {
        return reviewService.uploadMedia(principal.getId(), reviewId, files);
    }

    @DeleteMapping("/reviews/{reviewId}/media/{mediaId}")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public MessageResponse deleteMedia(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable UUID reviewId, @PathVariable UUID mediaId,
                                       HttpServletRequest httpRequest) {
        return reviewService.deleteMedia(principal.getId(), reviewId, mediaId, context(httpRequest));
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
