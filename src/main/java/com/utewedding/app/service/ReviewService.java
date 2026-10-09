package com.utewedding.app.service;

import com.utewedding.app.dto.request.CreateProductReviewRequest;
import com.utewedding.app.dto.request.ModerateProductReviewRequest;
import com.utewedding.app.dto.request.ReportProductReviewRequest;
import com.utewedding.app.dto.request.UpdateProductReviewRequest;
import com.utewedding.app.dto.response.MessageResponse;
import com.utewedding.app.dto.response.ProductReviewResponse;
import com.utewedding.app.dto.response.ProductReviewMediaResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public interface ReviewService {
    Page<ProductReviewResponse> list(UUID productId, int page, int size);
    Page<ProductReviewResponse> moderationQueue(int page, int size);
    ProductReviewResponse create(UUID userId, UUID productId, CreateProductReviewRequest request,
                                 RequestContext context);
    ProductReviewResponse update(UUID userId, UUID reviewId, UpdateProductReviewRequest request,
                                 RequestContext context);
    MessageResponse delete(UUID userId, UUID reviewId, RequestContext context);
    MessageResponse report(UUID userId, UUID reviewId, ReportProductReviewRequest request,
                           RequestContext context);
    ProductReviewResponse moderate(UUID adminId, UUID reviewId,
                                   ModerateProductReviewRequest request, RequestContext context);
    List<ProductReviewMediaResponse> uploadMedia(UUID userId, UUID reviewId, MultipartFile[] files);
    MessageResponse deleteMedia(UUID userId, UUID reviewId, UUID mediaId, RequestContext context);
    com.utewedding.app.dto.response.ReviewEligibility eligibility(UUID userId, UUID productId);
    Page<ProductReviewResponse> mine(UUID userId, int page, int size);
    Page<ProductReviewResponse> searchModeration(String query, com.utewedding.app.domain.ProductReviewStatus status,
                                                  Integer rating, UUID productId, UUID userId,
                                                  java.time.Instant from, java.time.Instant to, int page, int size);
    Page<AdminReviewRow> searchModerationRows(String query, com.utewedding.app.domain.ProductReviewStatus status,
                                              Integer rating, UUID productId, UUID userId,
                                              java.time.Instant from, java.time.Instant to, int page, int size);
    Page<com.utewedding.app.domain.entity.ProductReviewReportEntity> reports(
            com.utewedding.app.domain.ProductReviewReportStatus status, int page, int size);
    Page<ReviewReportView> reportViews(com.utewedding.app.domain.ProductReviewReportStatus status,
                                       int page, int size);
    void resolveReport(UUID actorId, UUID reportId, com.utewedding.app.domain.ProductReviewReportStatus status,
                       RequestContext context);
}
